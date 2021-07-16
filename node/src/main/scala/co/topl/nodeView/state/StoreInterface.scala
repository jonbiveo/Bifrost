package co.topl.nodeView.state

import io.iohk.iodb.{ByteArrayWrapper, LSMStore}

trait StoreInterface extends AutoCloseable {

  protected val storage: LSMStore

  /** method to retrieve data from storage */
  protected def getFromStorage(id: Array[Byte]): Option[Array[Byte]] =
    storage.get(ByteArrayWrapper(id)).map(_.data)

  /** method to close storage on termination */
  override def close(): Unit =
    storage.close()

}
