/*
 * Copyright (c) 2000, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.NotYetBoundException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import sun.net.NetHooks;
import sun.net.ext.ExtendedSocketOptions;
import static sun.net.ext.ExtendedSocketOptions.SOCK_STREAM;

/**
 * An implementation of ServerSocketChannels
 */

class ServerSocketChannelImpl
    extends ServerSocketChannel
    implements SelChImpl
{
    // Used to make native close and configure calls
    private static NativeDispatcher nd;

    // Our file descriptor
    private final FileDescriptor fd;
    private final int fdVal;

    // Lock held by thread currently blocked on this channel
    private final ReentrantLock acceptLock = new ReentrantLock();

    // Lock held by any thread that modifies the state fields declared below
    // DO NOT invoke a blocking I/O operation while holding this lock!
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition stateCondition = stateLock.newCondition();

    // -- The following fields are protected by stateLock

    // Channel state, increases monotonically
    private static final int ST_INUSE = 0;
    private static final int ST_CLOSING = 1;
    private static final int ST_CLOSED = 2;
    private int state;

    // ID of native thread currently blocked in this channel, for signalling
    private long thread;

    // Binding
    private InetSocketAddress localAddress; // null => unbound

    // set true when exclusive binding is on and SO_REUSEADDR is emulated
    private boolean isReuseAddress;

    // Our socket adaptor, if any
    private ServerSocket socket;

    // -- End of fields protected by stateLock


    ServerSocketChannelImpl(SelectorProvider sp) {
        super(sp);
        this.fd =  Net.serverSocket(true);
        this.fdVal = IOUtil.fdVal(fd);
    }

    ServerSocketChannelImpl(SelectorProvider sp, FileDescriptor fd, boolean bound)
        throws IOException
    {
        super(sp);
        this.fd =  fd;
        this.fdVal = IOUtil.fdVal(fd);
        if (bound) {
            stateLock.lock();
            try {
                localAddress = Net.localAddress(fd);
            } finally {
                stateLock.unlock();
            }
        }
    }

    // @throws ClosedChannelException if channel is closed
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen())
            throw new ClosedChannelException();
    }

    @Override
    public ServerSocket socket() {
        stateLock.lock();
        try {
            if (socket == null)
                socket = ServerSocketAdaptor.create(this);
            return socket;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        stateLock.lock();
        try {
            ensureOpen();
            return (localAddress == null)
                    ? null
                    : Net.getRevealedLocalAddress(localAddress);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value)
        throws IOException
    {
        Objects.requireNonNull(name);
        if (!supportedOptions().contains(name))
            throw new UnsupportedOperationException("'" + name + "' not supported");
        stateLock.lock();
        try {
            ensureOpen();

            if (name == StandardSocketOptions.IP_TOS) {
                ProtocolFamily family = Net.isIPv6Available() ?
                    StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;
                Net.setSocketOption(fd, family, name, value);
                return this;
            }

            if (name == StandardSocketOptions.SO_REUSEADDR && Net.useExclusiveBind()) {
                // SO_REUSEADDR emulated when using exclusive bind
                isReuseAddress = (Boolean)value;
            } else {
                // no options that require special handling
                Net.setSocketOption(fd, Net.UNSPEC, name, value);
            }
            return this;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOption(SocketOption<T> name)
        throws IOException
    {
        Objects.requireNonNull(name);
        if (!supportedOptions().contains(name))
            throw new UnsupportedOperationException("'" + name + "' not supported");

        stateLock.lock();
        try {
            ensureOpen();
            if (name == StandardSocketOptions.SO_REUSEADDR && Net.useExclusiveBind()) {
                // SO_REUSEADDR emulated when using exclusive bind
                return (T)Boolean.valueOf(isReuseAddress);
            }
            // no options that require special handling
            return (T) Net.getSocketOption(fd, Net.UNSPEC, name);
        } finally {
            stateLock.unlock();
        }
    }

    private static class DefaultOptionsHolder {
        static final Set<SocketOption<?>> defaultOptions = defaultOptions();

        private static Set<SocketOption<?>> defaultOptions() {
            HashSet<SocketOption<?>> set = new HashSet<>();
            set.add(StandardSocketOptions.SO_RCVBUF);
            set.add(StandardSocketOptions.SO_REUSEADDR);
            if (Net.isReusePortAvailable()) {
                set.add(StandardSocketOptions.SO_REUSEPORT);
            }
            set.add(StandardSocketOptions.IP_TOS);
            set.addAll(ExtendedSocketOptions.options(SOCK_STREAM));
            return Collections.unmodifiableSet(set);
        }
    }

    @Override
    public final Set<SocketOption<?>> supportedOptions() {
        return DefaultOptionsHolder.defaultOptions;
    }

    @Override
    public ServerSocketChannel bind(SocketAddress local, int backlog) throws IOException {
        stateLock.lock();
        try {
            ensureOpen();
            if (localAddress != null)
                throw new AlreadyBoundException();
            InetSocketAddress isa = (local == null)
                                    ? new InetSocketAddress(0)
                                    : Net.checkAddress(local);
            SecurityManager sm = System.getSecurityManager();
            if (sm != null)
                sm.checkListen(isa.getPort());
            NetHooks.beforeTcpBind(fd, isa.getAddress(), isa.getPort());
            Net.bind(fd, isa.getAddress(), isa.getPort());
            Net.listen(fd, backlog < 1 ? 50 : backlog);
            localAddress = Net.localAddress(fd);
        } finally {
            stateLock.unlock();
        }
        return this;
    }

    /**
     * Marks the beginning of an I/O operation that might block.
     *
     * @throws ClosedChannelException if the channel is closed
     * @throws NotYetBoundException if the channel's socket has not been bound yet
     */
    private void begin(boolean blocking) throws ClosedChannelException {
        if (blocking)
            begin();  // set blocker to close channel if interrupted
        stateLock.lock();
        try {
            ensureOpen();
            if (localAddress == null)
                throw new NotYetBoundException();
            if (blocking)
                thread = NativeThread.current();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Marks the end of an I/O operation that may have blocked.
     *
     * @throws AsynchronousCloseException if the channel was closed due to this
     * thread being interrupted on a blocking I/O operation.
     */
    private void end(boolean blocking, boolean completed)
        throws AsynchronousCloseException
    {
        if (blocking) {
            stateLock.lock();
            try {
                thread = 0;
                if (state == ST_CLOSING) {
                    stateCondition.signalAll();
                }
            } finally {
                stateLock.unlock();
            }
            end(completed);
        }
    }

    @Override
    public SocketChannel accept() throws IOException {
        acceptLock.lock();
        try {
            int n = 0;
            FileDescriptor newfd = new FileDescriptor();
            InetSocketAddress[] isaa = new InetSocketAddress[1];

            boolean blocking = isBlocking();
            try {
                begin(blocking);
                n = Net.accept(this.fd, newfd, isaa);
                if (blocking) {
                    while (IOStatus.okayToRetry(n) && isOpen()) {
                        park(Net.POLLIN);
                        n = Net.accept(this.fd, newfd, isaa);
                    }
                }
            } finally {
                end(blocking, n > 0);
                assert IOStatus.check(n);
            }
        } finally {
            acceptLock.unlock();
        }

        if (n > 0) {
            return finishAccept(newfd, isaa[0]);
        } else {
            return null;
        }
    }

    /**
     * Accepts a new connection with a given timeout. This method requires the
     * channel to be configured in blocking mode.
     *
     * @apiNote This method is for use by the socket adaptor.
     *
     * @param nanos the timeout, in nanoseconds
     * @throws IllegalBlockingModeException if the channel is configured non-blocking
     * @throws SocketTimeoutException if the timeout expires
     */
    SocketChannel blockingAccept(long nanos) throws IOException {
        int n = 0;
        FileDescriptor newfd = new FileDescriptor();
        InetSocketAddress[] isaa = new InetSocketAddress[1];

        acceptLock.lock();
        try {
            // check that channel is configured blocking
            if (!isBlocking())
                throw new IllegalBlockingModeException();

            try {
                begin(true);
                // change socket to non-blocking
                lockedConfigureBlocking(false);
                try {
                    long startNanos = System.nanoTime();
                    n = Net.accept(fd, newfd, isaa);
                    while (n == IOStatus.UNAVAILABLE && isOpen()) {
                        long remainingNanos = nanos - (System.nanoTime() - startNanos);
                        if (remainingNanos <= 0) {
                            throw new SocketTimeoutException("Accept timed out");
                        }
                        park(Net.POLLIN, remainingNanos);
                        n = Net.accept(fd, newfd, isaa);
                    }
                } finally {
                    // restore socket to blocking mode
                    lockedConfigureBlocking(true);
                }
            } finally {
                end(true, n > 0);
            }
        } finally {
            acceptLock.unlock();
        }

        assert n > 0;
        return finishAccept(newfd, isaa[0]);
    }

    private SocketChannel finishAccept(FileDescriptor newfd, InetSocketAddress isa)
        throws IOException
    {
        try {
            // newly accepted socket is initially in blocking mode
            IOUtil.configureBlocking(newfd, true);

            InetSocketAddress isa = isaa[0];
            SocketChannel sc = new SocketChannelImpl(provider(), newfd, isa);

            // check permitted to accept connections from the remote address
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                try {
                    sm.checkAccept(isa.getAddress().getHostAddress(), isa.getPort());
                } catch (SecurityException x) {
                    sc.close();
                    throw x;
                }
            }
            return sc;

        } finally {
            acceptLock.unlock();
        }
    }

    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
        acceptLock.lock();
        try {
            lockedConfigureBlocking(block);
        } finally {
            acceptLock.unlock();
        }
    }

    /**
     * Adjust the blocking mode while holding acceptLock.
     */
    private void lockedConfigureBlocking(boolean block) throws IOException {
        assert acceptLock.isHeldByCurrentThread();
        stateLock.lock();
        try {
            ensureOpen();
            IOUtil.configureBlocking(fd, block);
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Invoked by implCloseChannel to close the channel.
     *
     * This method waits for outstanding I/O operations to complete. When in
     * blocking mode, the socket is pre-closed and the threads in blocking I/O
     * operations are signalled to ensure that the outstanding I/O operations
     * complete quickly.
     *
     * The socket is closed by this method when it is not registered with a
     * Selector. Note that a channel configured blocking may be registered with
     * a Selector. This arises when a key is canceled and the channel configured
     * to blocking mode before the key is flushed from the Selector.
     */
    private void lockedConfigureBlocking(boolean block) throws IOException {
        assert acceptLock.isHeldByCurrentThread();
        synchronized (stateLock) {
            ensureOpen();
            IOUtil.configureBlocking(fd, block);
        }
    }

    /**
     * Closes the socket if there are no accept in progress and the channel is
     * not registered with a Selector.
     */
    private boolean tryClose() throws IOException {
        assert Thread.holdsLock(stateLock) && state == ST_CLOSING;
        if ((thread == 0) && !isRegistered()) {
            state = ST_CLOSED;
            nd.close(fd);
            return true;
        } else {
            return false;
        }
    }

        // set state to ST_CLOSING
        stateLock.lock();
        try {
            assert state < ST_CLOSING;
            state = ST_CLOSING;
            blocking = isBlocking();
        } finally {
            stateLock.unlock();
        }

        // wait for any outstanding accept to complete
        if (blocking) {
            stateLock.lock();
            try {
                assert state == ST_CLOSING;
                long th = thread;
                if (th != 0) {
                    nd.preClose(fd);
                    NativeThread.signal(th);

                    // wait for accept operation to end
                    while (thread != 0) {
                        try {
                            stateCondition.await();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }
            } finally {
                stateLock.unlock();
            }
        }
    }

        // set state to ST_KILLPENDING
        stateLock.lock();
        try {
            assert state == ST_CLOSING;
            state = ST_KILLPENDING;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Invoked by implCloseChannel to close the channel.
     */
    @Override
    protected void implCloseSelectableChannel() throws IOException {
        assert !isOpen();
        if (isBlocking()) {
            implCloseBlockingMode();
        } else {
            implCloseNonBlockingMode();
        }
    }

    @Override
    public void kill() throws IOException {
        stateLock.lock();
        try {
            if (state == ST_KILLPENDING) {
                state = ST_KILLED;
                nd.close(fd);
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Returns true if channel's socket is bound
     */
    boolean isBound() {
        stateLock.lock();
        try {
            return localAddress != null;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Returns the local address, or null if not bound
     */
    InetSocketAddress localAddress() {
        stateLock.lock();
        try {
            return localAddress;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Translates native poll revent set into a ready operation set
     */
    public boolean translateReadyOps(int ops, int initialOps, SelectionKeyImpl ski) {
        int intOps = ski.nioInterestOps();
        int oldOps = ski.nioReadyOps();
        int newOps = initialOps;

        if ((ops & Net.POLLNVAL) != 0) {
            // This should only happen if this channel is pre-closed while a
            // selection operation is in progress
            // ## Throw an error if this channel has not been pre-closed
            return false;
        }

        if ((ops & (Net.POLLERR | Net.POLLHUP)) != 0) {
            newOps = intOps;
            ski.nioReadyOps(newOps);
            return (newOps & ~oldOps) != 0;
        }

        if (((ops & Net.POLLIN) != 0) &&
            ((intOps & SelectionKey.OP_ACCEPT) != 0))
                newOps |= SelectionKey.OP_ACCEPT;

        ski.nioReadyOps(newOps);
        return (newOps & ~oldOps) != 0;
    }

    public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl ski) {
        return translateReadyOps(ops, ski.nioReadyOps(), ski);
    }

    public boolean translateAndSetReadyOps(int ops, SelectionKeyImpl ski) {
        return translateReadyOps(ops, 0, ski);
    }

    /**
     * Translates an interest operation set into a native poll event set
     */
    public int translateInterestOps(int ops) {
        int newOps = 0;
        if ((ops & SelectionKey.OP_ACCEPT) != 0)
            newOps |= Net.POLLIN;
        return newOps;
    }

    public FileDescriptor getFD() {
        return fd;
    }

    public int getFDVal() {
        return fdVal;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClass().getName());
        sb.append('[');
        if (!isOpen()) {
            sb.append("closed");
        } else {
            stateLock.lock();
            try {
                InetSocketAddress addr = localAddress;
                if (addr == null) {
                    sb.append("unbound");
                } else {
                    sb.append(Net.getRevealedLocalAddressAsString(addr));
                }
            } finally {
                stateLock.unlock();
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Accept a connection on a socket.
     *
     * @implNote Wrap native call to allow instrumentation.
     */
    private int accept(FileDescriptor ssfd,
                       FileDescriptor newfd,
                       InetSocketAddress[] isaa)
        throws IOException
    {
        return accept0(ssfd, newfd, isaa);
    }

    // -- Native methods --

    // Accepts a new connection, setting the given file descriptor to refer to
    // the new socket and setting isaa[0] to the socket's remote address.
    // Returns 1 on success, or IOStatus.UNAVAILABLE (if non-blocking and no
    // connections are pending) or IOStatus.INTERRUPTED.
    //
    private native int accept0(FileDescriptor ssfd,
                               FileDescriptor newfd,
                               InetSocketAddress[] isaa)
        throws IOException;

    private static native void initIDs();

    static {
        IOUtil.load();
        initIDs();
        nd = new SocketDispatcher();
    }

}
