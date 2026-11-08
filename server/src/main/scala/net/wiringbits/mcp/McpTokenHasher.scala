package net.wiringbits.mcp

import java.security.MessageDigest
import java.util.HexFormat

object McpTokenHasher {

  def hashHex(s: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")))
}
