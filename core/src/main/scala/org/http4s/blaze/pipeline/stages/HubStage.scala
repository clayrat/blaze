package org.http4s.blaze
package pipeline
package stages


import java.util.HashMap
import java.nio.channels.NotYetConnectedException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.http4s.blaze.pipeline.Command._



abstract class HubStage[I](ec: ExecutionContext) extends TailStage[I] {
  
  type Out                  // type of messages accepted by the nodes
  type Key                  // type that will uniquely determine the nodes
  protected type Attachment // state that can be appended to the node

  def name: String = "HubStage"

  sealed trait Node {
    /** Identifier of this node */
    def key: Key

    def attachment: Attachment

    def inboundCommand(command: InboundCommand): Unit

    /** Shuts down the [[Node]]
      * This [[Node]] is sent the [[Disconnected]] [[InboundCommand]],
      * any pending read requests are sent [[EOF]], and removes it from the [[HubStage]] */
    def shutdown(): Unit
  }

  /** called when a node requests a write operation */
  protected def onNodeWrite(node: Node, data: Seq[Out]): Future[Unit]

  /** called when a node needs more data */
  protected def onNodeRead(node: Node, size: Int): Future[Out]

  /** called when a node sends an outbound command */
  protected def onNodeCommand(node: Node, cmd: OutboundCommand): Unit
  
  protected def nodeBuilder(): LeafBuilder[Out]

  

  ////////////////////////////////////////////////////////////////////////////////////

  private implicit def _ec = ec
  private val nodeMap = new HashMap[Key, NodeHead]()

  /** Make a new node and connect it to the hub
    * @param key key which identifies this node
    * @return the newly created node
    */
  protected def makeNode(key: Key, attachment: Attachment): Node = {
    val node = new NodeHead(key, attachment)
    nodeBuilder().base(node)
    val old = nodeMap.put(key, node)
    if (old != null) {
      logger.warn(s"New Node $old with key $key created which replaced an existing Node")
      old.inboundCommand(Disconnected)
    }
    node.stageStartup()
    node
  }

  /** Get a child [[Node]]
    * @param key K specifying the [[Node]] of interest
    * @return `Option[Node]`
    */
  final protected def getNode(key: Key): Option[Node] = Option(nodeMap.get(key))

  /** Get an iterator over the nodes attached to this [[HubStage]] */
  final protected def nodeIterator(): Iterator[Node] = new Iterator[Node] {
    private val it = nodeMap.values().iterator()

    override def hasNext: Boolean = it.hasNext
    override def next(): Node = it.next()
  }

  /** Closes all the nodes of this hub stage */
  protected def closeAllNodes(): Unit = {
    val values = nodeMap.values().iterator()
    while (values.hasNext) {
      val node = values.next()
      node.stageShutdown()
      node.sendInboundCommand(Disconnected)
    }
    nodeMap.clear()
  }

  /** Remove the specified [[Node]] from this [[HubStage]] */
  final protected def removeNode(node: Node): Unit = removeNode(node.key)

  /** Remove the [[Node]] from this [[HubStage]]
    * This method should only be called from 
    * @param key K specifying the [[Node]]
    */
  protected def removeNode(key: Key): Option[Node] = {
    val node = nodeMap.remove(key)
    if (node != null) {
      node.stageShutdown()
      node.sendInboundCommand(Disconnected)
      Some(node)
    }
    else None
  }

  override protected def stageShutdown(): Unit = {
    closeAllNodes()
    super.stageShutdown()
  }


  private[HubStage] class NodeHead(val key: Key, val attachment: Attachment) extends HeadStage[Out] with Node {

    def name: String = "HubStage Hub Head"
    private var connected = false
    private var initialized = false

    def shutdown(): Unit = removeNode(key)

    override def writeRequest(data: Out): Future[Unit] = writeRequest(data::Nil)

    override def writeRequest(data: Seq[Out]): Future[Unit] = {
      if (connected) onNodeWrite(this, data)
      else if (!initialized) {
        logger.error(s"Disconnected node with key $key attempting write request")
        Future.failed(new NotYetConnectedException)
      }
      else Future.failed(EOF)
    }

    override def readRequest(size: Int): Future[Out] =  {
      if (connected) onNodeRead(this, size)
      else if (!initialized) {
        logger.error(s"Disconnected node with key $key attempting read request")
        Future.failed(new NotYetConnectedException)
      } else Future.failed(EOF)
    }

    override def outboundCommand(cmd: OutboundCommand): Unit =
      onNodeCommand(this, cmd)

    override def stageStartup(): Unit = {
      connected = true
      initialized = true
      sendInboundCommand(Connected)
    }

    override def stageShutdown(): Unit = {
      connected = false
      super.stageShutdown()
    }
  }
}
