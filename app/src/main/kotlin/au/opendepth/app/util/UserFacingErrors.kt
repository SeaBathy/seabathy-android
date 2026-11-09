package au.opendepth.app.util

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Turns a failure into text that is safe to show the user.
 *
 * This app's own errors are plain [IOException]s (or subclasses declared in
 * this app) whose messages were written for the user, so those pass through.
 * Everything else — SQLite, platform permission, network-stack and runtime
 * exceptions — carries wording meant for a developer (error codes, content
 * URIs, class names), so it is mapped to a known plain-language message or
 * replaced by the caller's [fallback]. The raw exception still goes to
 * DiagnosticLog at each call site.
 */
object UserFacingErrors {

    private const val APP_PACKAGE = "au.opendepth.app"
    private val ERRNO = Regex("\\bE[A-Z]{2,}\\b \\(")

    fun describe(error: Throwable, fallback: String): String = when (error) {
        is UnknownHostException, is SocketTimeoutException, is SocketException ->
            "Couldn't reach the server. Check your internet connection and try again."

        is SSLException ->
            "Couldn't open a secure connection to the server. Check the device's date and time, then try again."

        is FileNotFoundException ->
            "The file couldn't be found. It may have been moved, renamed or deleted."

        is EOFException ->
            "The file ended unexpectedly. It may be incomplete or damaged."

        is SecurityException ->
            "SeaBathy no longer has permission to read this file. Pick it again from your device."

        is OutOfMemoryError ->
            "This file is too large to open on this device."

        is IOException -> when {
            error.message.orEmpty().contains("ENOSPC") ->
                "There isn't enough free storage on this device. Free up some space and try again."
            // Platform I/O failures are also plain IOExceptions, but carry
            // an errno code ("EACCES (Permission denied)") meant for a developer.
            isAppAuthored(error) && !ERRNO.containsMatchIn(error.message.orEmpty()) ->
                error.message?.takeIf { it.isNotBlank() } ?: fallback
            else -> fallback
        }

        else -> fallback
    }

    private fun isAppAuthored(error: IOException): Boolean =
        error.javaClass == IOException::class.java ||
            error.javaClass.name.startsWith("$APP_PACKAGE.")
}
