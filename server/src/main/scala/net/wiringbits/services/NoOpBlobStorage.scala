package net.wiringbits.services

import scala.concurrent.Future

class NoOpBlobStorage extends BlobStorage {
  def put(storageKey: String, bytes: Array[Byte]): Future[Unit] = Future.unit
  def exists(storageKey: String): Future[Boolean] = Future.successful(false)
  def get(storageKey: String): Future[Option[Array[Byte]]] = Future.successful(None)
  def delete(storageKey: String): Future[Unit] = Future.unit
}
