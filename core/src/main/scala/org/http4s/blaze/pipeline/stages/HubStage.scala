package org.http4s.blaze
package pipeline
package stages


import java.util.HashMap
import java.nio.channels.NotYetConnectedException

import scala.concurrent.Future

import org.http4s.blaze.pipeline.Command._



abstract class HubStage[I] extends TailStage[I] {
  
  type Out                  // type of messages accepted by the nodes
  type Key                  // type that will uniquely determine the nodes
  protected type Attachment // state that can be appended to the node

  /** Will serve as the attachment point for each attached pipeline */
  sealed trait Node {
    /** Identifier of this node */
    val key: Key

    val attachment: Attachment

    def inboundCommand(command: InboundCommand): Unit

    /** Shuts down the [[Node]]
      * Any pending read requests are sent [[EOF]], and removes it from the [[HubStage]] */
    def stageShutdown(): Unit

    final def startNode(): Unit = inboundCommand(Connected)
  }

  /** called when a node requests a write operation */
  protected def onNodeWrite(node: Node, data: Seq[Out]): Future[Unit]

  /** called when a node needs more data */
  protected def onNodeRead(node: Node, size: Int): Future[Out]

  /** called when a node sends an outbound command
    * This includes Disconnect commands to give the Hub notice so
    * it can change any related state it may have */
  protected def onNodeCommand(node: Node, cmd: OutboundCommand): Unit

  /** Constructs a new LeafBuilder[Out] to make nodes from */
  protected def nodeBuilder(): LeafBuilder[Out]
  

  ////////////////////////////////////////////////////////////////////////////////////

  private val nodeMap = new HashMap[Key, NodeHead]()

  /** Make a new node and connect it to the hub if the key doesn't already exist
    * @param key key which identifies this node
    * @return the newly created node in an unstarted state. To begin the node
    *         send a [[Connected]] command or call its `startNode()` method
    */
  protected def makeNode(key: Key, attachment: => Attachment): Option[Node] = {
    if (!nodeMap.containsKey(key)) {
      val node = new NodeHead(key, attachment)
      nodeMap.put(key, node)
      nodeBuilder().base(node)
      Some(node)
    }
    else None
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
      values.remove()
      checkShutdown(node)
    }
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
      checkShutdown(node)
      Some(node)
    }
    else None
  }

  override protected def stageShutdown(): Unit = {
    closeAllNodes()
    super.stageShutdown()
  }

  ////////////////////////////////////////////////////////////

  private def checkShutdown(node: NodeHead): Unit = {
    if (node.isConnected()) node.stageShutdown()
  }

  ////////////////////////////////////////////////////////////

  private[HubStage] class NodeHead(val key: Key, val attachment: Attachment) extends HeadStage[Out] with Node {

    def name: String = "HubStage Hub Head"
    private var connected = false
    private var initialized = false

    def isConnected(): Boolean = connected

    override def writeRequest(data: Out): Future[Unit] = writeRequest(data::Nil)

    override def writeRequest(data: Seq[Out]): Future[Unit] = {
      if (connected) onNodeWrite(this, data)
      else onNotReady()
    }

    override def readRequest(size: Int): Future[Out] =  {
      if (connected) onNodeRead(this, size)
      else onNotReady()
    }

    override def outboundCommand(cmd: OutboundCommand): Unit =
      onNodeCommand(this, cmd)

    override def stageStartup(): Unit = {
      connected = true
      initialized = true
    }

    override def stageShutdown(): Unit = {
      connected = false
      removeNode(key)
      super.stageShutdown()
    }

    private def onNotReady(): Future[Nothing] = {
      if (!initialized) {
        logger.error(s"Disconnected node with key $key attempting write request")
        Future.failed(new NotYetConnectedException)
      }
      else Future.failed(EOF)
    }
  }
}
