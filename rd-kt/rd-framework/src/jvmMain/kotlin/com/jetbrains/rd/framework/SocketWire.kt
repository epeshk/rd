package com.jetbrains.rd.framework

import com.jetbrains.rd.framework.base.WireBase
import com.jetbrains.rd.util.*
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.isAlive
import com.jetbrains.rd.util.lifetime.plusAssign
import com.jetbrains.rd.util.reactive.*
import com.jetbrains.rd.util.threading.ByteBufferAsyncProcessor
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.time.Duration
import kotlin.concurrent.thread

private fun InputStream.readByteArray(a : ByteArray): Boolean {
    var pos = 0
    while (pos < a.size) {
        val b = read(a, pos, a.size - pos)
        if (b == -1)
            return false
        pos += b
    }
    return true
}


private fun InputStream.readInt32() : Int? {
    val b1 = read().apply { if (this < 0) return null }
    val b2 = read().apply { if (this < 0) return null }
    val b3 = read().apply { if (this < 0) return null }
    val b4 = read().apply { if (this < 0) return null }
    val res = b1 or (b2 shl 8) or (b3 shl 16) or (b4 shl 24)

    return res
}

private fun InputStream.readInt64() : Long? {
    val b1 = read().toLong().apply { if (this < 0) return null }
    val b2 = read().toLong().apply { if (this < 0) return null }
    val b3 = read().toLong().apply { if (this < 0) return null }
    val b4 = read().toLong().apply { if (this < 0) return null }
    val b5 = read().toLong().apply { if (this < 0) return null }
    val b6 = read().toLong().apply { if (this < 0) return null }
    val b7 = read().toLong().apply { if (this < 0) return null }
    val b8 = read().toLong().apply { if (this < 0) return null }
    val res = b1 or (b2 shl 8) or (b3 shl 16) or (b4 shl 24) or
            (b5 shl 32) or (b6 shl 40) or (b7 shl 48) or (b8 shl 56)


    return res
}


class SocketWire {
    companion object {
        val timeout: Duration = Duration.ofMillis(500)
        private const val ack_msg_len: Int = -1
        private const val pkg_header_len = 12
        const val disconnectedPauseReason = "Disconnected"

    }

    abstract class Base protected constructor(val id: String, private val lifetime: Lifetime, scheduler: IScheduler) : WireBase(scheduler) {

        protected val logger: Logger = getLogger(this::class)
        val socketProvider = OptProperty<Socket>()

        private lateinit var output : OutputStream
        private lateinit var socketInput : InputStream
        private lateinit var pkgInput : InputStream

        protected val sendBuffer = ByteBufferAsyncProcessor("$id-AsyncSendProcessor", processor = ::send0)

        private val threadLocalBufferArray = ThreadLocal.withInitial { UnsafeBuffer(ByteArray(16384)) }

        protected val lock = Object()

        private var maxReceivedSeqn : Long = 0


        init {

            sendBuffer.pause(disconnectedPauseReason)
            sendBuffer.start()

            socketProvider.advise(lifetime) { socket ->

                logger.debug { "$id : connected" }

                output = socket.outputStream
                socketInput = socket.inputStream.buffered()
                pkgInput = PkgInputStream(socketInput)

                sendBuffer.reprocessUnacknowledged()
                sendBuffer.resume(disconnectedPauseReason)

                scheduler.queue { connected.value = true }

                try {
                    receiverProc(socket)
                } finally {
                    scheduler.queue { connected.value = false }
                    sendBuffer.pause(disconnectedPauseReason)
                    catchAndDrop { socket.close() }
                }
            }
        }

        private fun receiverProc(socket: Socket) {
            while (lifetime.isAlive) {
                try {
                    if (!socket.isConnected) {
                        logger.debug {"Stop receive messages because socket disconnected" }
                        break
                    }

                    if (!readMsg()) {
                        logger.debug { "$id: Connection was gracefully shutdown" }
                        break
                    }
                } catch (ex: Throwable) {
                    when (ex) {
                        is SocketException, is EOFException -> logger.debug {"Exception in SocketWire.Receive:  $id: $ex" }
                        else -> logger.error("$id caught processing", ex)
                    }

                    break
                }
            }
        }

        private fun readMsg() : Boolean {

            val seqnAtStart = maxReceivedSeqn

            val len = pkgInput.readInt32() ?: return false
            require(len > 0) {"len > 0: $len"}
            require (len < 300_000_000) { "Possible OOM: array_len=$len(0x${len.toString(16)})" }

            val data = ByteArray(len)
            if (!pkgInput.readByteArray(data))
                return false

            if (maxReceivedSeqn > seqnAtStart)
                sendAck(maxReceivedSeqn)

            val unsafeBuffer = UnsafeBuffer(data)
            val id = RdId.read(unsafeBuffer)
            messageBroker.dispatch(id, unsafeBuffer)

            return true
        }


        inner class PkgInputStream(private val stream: InputStream) : InputStream() {
            var pkg: ByteArray = ByteArray(0)
            var pos: Int = 0

            override fun read(): Int {
                if (pos < pkg.size)
                    return pkg[pos++].toInt() and 0xff

                while (true) {
                    val len = stream.readInt32() ?: return -1
                    val seqn = stream.readInt64() ?: return -1


                    if (len == ack_msg_len)
                        sendBuffer.acknowledge(seqn)
                    else {
                        require(len > 0) {"len > 0: $len"}
                        require (len < 300_000_000) { "Possible OOM: array_len=$len(0x${len.toString(16)})" }

                        pkg = ByteArray(len)
                        pos = 0
                        stream.readByteArray(pkg)

                        if (seqn > maxReceivedSeqn) {
                            maxReceivedSeqn = seqn
                            return pkg[pos++].toInt() and 0xff
                        } else
                            sendAck(seqn)
                    }

                }
            }

        }

        private fun sendAck(seqn: Long) {
            try {
                ackPkgHeader.rewind()
                ackPkgHeader.writeInt(ack_msg_len)
                ackPkgHeader.writeLong(seqn)

                synchronized(socketSendLock) {
                    output.write(ackPkgHeader.getArray(), 0, pkg_header_len)
                }
            } catch (ex: SocketException) {
                logger.warn { "$id: Exception raised during ACK, seqn = $seqn" }
            }
        }



        private var sentSeqn = 0L
        private val socketSendLock = Any()
        private val sendPkgHeader = createAbstractBuffer()
        private val ackPkgHeader = createAbstractBuffer()

        private fun send0(chunk: ByteBufferAsyncProcessor.Chunk) {
            try {
                if (chunk.isNotProcessed)
                    chunk.seqn = ++sentSeqn


                sendPkgHeader.rewind()
                sendPkgHeader.writeInt(chunk.ptr)
                sendPkgHeader.writeLong(chunk.seqn)

                synchronized(socketSendLock) {
                    output.write(sendPkgHeader.getArray(), 0, pkg_header_len)
                    output.write(chunk.data, 0, chunk.ptr)
                }
            } catch (ex: SocketException) {
                sendBuffer.pause(disconnectedPauseReason)

            }
        }

        override fun send(id: RdId, writer: (AbstractBuffer) -> Unit) {
            require(!id.isNull) { "id mustn't be null" }

            val unsafeBuffer = threadLocalBufferArray.get()
            val initialPosition = unsafeBuffer.position
            try {

                unsafeBuffer.writeInt(0) //placeholder for length

                id.write(unsafeBuffer) //write id
                writer(unsafeBuffer) //write rest

                val len = unsafeBuffer.position - initialPosition

                unsafeBuffer.position = initialPosition
                unsafeBuffer.writeInt(len - 4)

                val bytes = unsafeBuffer.getArray()
                sendBuffer.put(bytes, initialPosition, len)
            } finally {
                unsafeBuffer.position = initialPosition
            }
        }
    }


    class Client(lifetime : Lifetime, scheduler: IScheduler, port : Int, optId: String? = null, hostAddress: InetAddress = InetAddress.getLoopbackAddress()) : Base(optId ?:"ClientSocket", lifetime, scheduler) {

        init {

            var socket : Socket? = null
            val thread = thread(name = id, isDaemon = true) {
                try {
                    while (lifetime.isAlive) {
                        try {
                            val s = Socket()
                            s.tcpNoDelay = true

                            // On windows connect will try to send SYN 3 times with interval of 500ms (total time is 1second)
                            // Connect timeout doesn't work if it's more than 1 second. But we don't need it because we can close socket any moment.

                            //https://stackoverflow.com/questions/22417228/prevent-tcp-socket-connection-retries
                            //HKLM\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\TcpMaxConnectRetransmissions
                            logger.debug { "$id : connecting to $hostAddress:$port" }
                            s.connect(InetSocketAddress(hostAddress, port))

                            synchronized(lock) {
                                if (!lifetime.isAlive) {
                                    logger.debug { "$id : connected, but lifetime is already canceled, closing socket"}
                                    catchAndDrop {s.close()}
                                    return@thread
                                }
                                else
                                    socket = s
                            }

                            socketProvider.set(s)
                            logger.debug { "$id: receiverProc finished " }

                        } catch (e: ConnectException) {

                            val shouldReconnect = synchronized(lock) {
                                if (lifetime.isAlive) {
                                    lock.wait(timeout.toMillis())
                                    lifetime.isAlive
                                } else false

                            }
                            if (shouldReconnect)
                                continue
                            else
                                break
                        }
                    }

                } catch (ex: SocketException) {
                    logger.info {"$id: closed with exception: $ex"}
                }
            }


            lifetime += {
                logger.info {"$id: start terminating lifetime"}

                val sendBufferStopped = sendBuffer.stop(timeout)
                logger.debug{"$id: send buffer stopped, success: $sendBufferStopped"}

                synchronized(lock) {
                    logger.debug{"$id: closing socket"}
                    catch {socket?.close()}
                    lock.notifyAll()
                }

                logger.debug { "$id: waiting for receiver thread" }
                thread.join()
                logger.info { "$id: termination finished" }
            }

        }
    }


    class Server internal constructor(lifetime : Lifetime, scheduler: IScheduler, ss: ServerSocket, optId: String? = null, allowReconnect: Boolean) : Base(optId ?:"ServerSocket", lifetime, scheduler) {
        val port : Int = ss.localPort

        companion object {
            internal fun createServerSocket(port : Int?, allowRemoteConnections: Boolean) : ServerSocket {
                val address = if (allowRemoteConnections) null else InetAddress.getByName("127.0.0.1")
                return ServerSocket(port?:0, 0, address)
            }
        }

        constructor (lifetime : Lifetime, scheduler: IScheduler, port : Int?, optId: String? = null, allowRemoteConnections: Boolean = false) : this(lifetime, scheduler, createServerSocket(port, allowRemoteConnections), optId, allowReconnect = true)

        init {
            var socket : Socket? = null
            val thread = thread(name = id, isDaemon = true) {
                while (lifetime.isAlive) {
                    try {
                        logger.debug { "$id: listening ${ss.localSocketAddress}" }
                        val s = ss.accept() //could be terminated by close
                        s.tcpNoDelay = true

                        synchronized(lock) {
                            if (!lifetime.isAlive) {
                                logger.debug { "$id : connected, but lifetime is already canceled, closing socket" }
                                catch { s.close() }
                                return@thread
                            } else
                                socket = s
                        }


                        socketProvider.set(s)
                    } catch (ex: SocketException) {
                        logger.debug { "$id closed with exception: $ex" }
                    } catch (ex: Exception) {
                        logger.error("$id closed with exception", ex)
                    }

                    if (!allowReconnect)
                        break
                }

            }


            lifetime.onTerminationIfAlive {
                logger.info {"$id: start terminating lifetime" }

                val sendBufferStopped = sendBuffer.stop(timeout)
                logger.debug {"$id: send buffer stopped, success: $sendBufferStopped"}

                catch {
                    logger.debug {"$id: closing server socket"}
                    ss.close()
                }
                catch {
                    synchronized(lock) {
                        logger.debug {"$id: closing socket"}
                        socket?.close()
                    }
                }

                logger.debug {"$id: waiting for receiver thread"}
                thread.join()
                logger.info{"$id: termination finished"}

            }
        }
    }



    data class WireParameters(val scheduler: IScheduler, val id: String?)
    class ServerFactory private constructor(lifetime : Lifetime, wireParametersFactory: () -> WireParameters, port : Int?, allowRemoteConnections: Boolean, set: ViewableSet<Server>) : IViewableSet<Server> by set {

        constructor(lifetime : Lifetime, wireParametersFactory: () -> WireParameters, port : Int?, allowRemoteConnections: Boolean = false) :
            this(lifetime, wireParametersFactory, port, allowRemoteConnections, ViewableSet<Server>())

        constructor(lifetime : Lifetime, scheduler: IScheduler, port : Int?, allowRemoteConnections: Boolean = false) :
                this(lifetime, { WireParameters(scheduler, null) }, port, allowRemoteConnections, ViewableSet<Server>())


        val localPort: Int

        init {
            val ss = Server.createServerSocket(port, allowRemoteConnections)
            localPort = ss.localPort

            fun rec() {
                val (scheduler, optId) = wireParametersFactory()
                val s = Server(lifetime, scheduler, ss, optId, allowReconnect = false)
                s.connected.whenTrue(lifetime) { lt ->
                    set.addUnique(lt, s)
                    rec()
                }
            }

            rec()
        }


    }

}


//todo remove
val IWire.serverPort: Int get() {
    val serverSocketWire = this as? SocketWire.Server ?: throw IllegalArgumentException("You must use SocketWire.Server to get server port")
    return serverSocketWire.port
}