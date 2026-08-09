package com.nscheatmanager.app.protocol

import java.io.IOException

sealed class ProtocolError(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Connection(cause: Throwable) : ProtocolError("Could not connect to the device", cause)
    class Timeout(val operation: String, cause: Throwable) :
        ProtocolError("Timed out while waiting for $operation", cause)

    class Disconnected(cause: Throwable? = null) : ProtocolError("The device disconnected", cause)
    class MalformedResponse(val response: String, cause: Throwable? = null) :
        ProtocolError("The device returned a malformed response", cause)

    class ResponseTooLarge(val limitBytes: Int) :
        ProtocolError("The device response exceeded $limitBytes bytes")

    class CommandTooLarge(val limitBytes: Int, val actualBytes: Long) :
        ProtocolError("The device command is $actualBytes bytes; maximum is $limitBytes bytes")
}
