package net.wiringbits.mcp

import java.security.MessageDigest

object McpTokenHasher {

  def hashHex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString
}
