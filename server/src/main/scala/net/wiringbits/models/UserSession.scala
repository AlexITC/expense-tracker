package net.wiringbits.models

import java.util.UUID

/** What the session cookie carries: the user and the users.session_version it was issued against. */
case class UserSession(userId: UUID, version: Int)
