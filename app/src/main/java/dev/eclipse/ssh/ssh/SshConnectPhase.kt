package dev.eclipse.ssh.ssh

/**
 * The two halves of bringing a session up, reported by [SshConnectionManager.connect] as it enters each.
 *
 * Not merged into [dev.eclipse.ssh.data.model.SessionConnectionState]: this is what the *transport
 * layer* is doing, and the session state is what the app is presenting. They are nearly parallel today
 * and deliberately not the same type - a reconnect attempt walks through both of these phases while the
 * session it belongs to stays `RECONNECTING`, and collapsing the two would make that impossible to say.
 */
enum class SshConnectPhase {
    /** Opening the socket, negotiating the proxy if there is one, exchanging keys. */
    HANDSHAKE,

    /** Transport up: offering credentials and waiting for the server's verdict. */
    AUTHENTICATE,
}
