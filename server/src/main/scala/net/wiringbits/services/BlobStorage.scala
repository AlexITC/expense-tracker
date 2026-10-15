package net.wiringbits.services

import scala.concurrent.Future

trait BlobStorage {
  def put(storageKey: String, bytes: Array[Byte]): Future[Unit]
  def exists(storageKey: String): Future[Boolean]
  def get(storageKey: String): Future[Option[Array[Byte]]]
  def delete(storageKey: String): Future[Unit]
}
