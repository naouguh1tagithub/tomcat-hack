/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.File;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;

import org.apache.juli.logging.Log;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.net.jsse.JSSESocketFactory;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.CounterLatch;
import org.apache.tomcat.util.threads.ResizableExecutor;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
/**
 *
 * @author fhanik
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public abstract class AbstractEndpoint {

    // -------------------------------------------------------------- Constants
    protected static final StringManager sm = StringManager.getManager("org.apache.tomcat.util.net.res");

    public static interface Handler {
        /**
         * Different types of socket states to react upon.
         */
        public enum SocketState {
            OPEN, CLOSED, LONG, ASYNC_END
        }
        

        /**
         * Obtain the GlobalRequestProcessor associated with the handler.
         */
        public Object getGlobal();
        
        
        /**
         * Recycle resources associated with the handler.
         */
        public void recycle();
    }

    protected enum BindState {
        UNBOUND, BOUND_ON_INIT, BOUND_ON_START
    }

    // Standard SSL Configuration attributes
    // JSSE
    // Standard configuration attribute names
    public static final String SSL_ATTR_ALGORITHM = "algorithm";
    public static final String SSL_ATTR_CLIENT_AUTH = "clientAuth";
    public static final String SSL_ATTR_KEYSTORE_FILE = "keystoreFile";
    public static final String SSL_ATTR_KEYSTORE_PASS = "keystorePass";
    public static final String SSL_ATTR_KEYSTORE_TYPE = "keystoreType";
    public static final String SSL_ATTR_KEYSTORE_PROVIDER = "keystoreProvider";
    public static final String SSL_ATTR_SSL_PROTOCOL = "sslProtocol";
    public static final String SSL_ATTR_CIPHERS = "ciphers";
    public static final String SSL_ATTR_KEY_ALIAS = "keyAlias";
    public static final String SSL_ATTR_KEY_PASS = "keyPass";
    public static final String SSL_ATTR_TRUSTSTORE_FILE = "truststoreFile";
    public static final String SSL_ATTR_TRUSTSTORE_PASS = "truststorePass";
    public static final String SSL_ATTR_TRUSTSTORE_TYPE = "truststoreType";
    public static final String SSL_ATTR_TRUSTSTORE_PROVIDER =
        "truststoreProvider";
    public static final String SSL_ATTR_TRUSTSTORE_ALGORITHM =
        "truststoreAlgorithm";
    public static final String SSL_ATTR_CRL_FILE =
        "crlFile";
    public static final String SSL_ATTR_TRUST_MAX_CERT_LENGTH =
        "trustMaxCertLength";
    public static final String SSL_ATTR_SESSION_CACHE_SIZE =
        "sessionCacheSize";
    public static final String SSL_ATTR_SESSION_TIMEOUT =
        "sessionTimeout";
    public static final String SSL_ATTR_ALLOW_UNSAFE_RENEG =
        "allowUnsafeLegacyRenegotiation";

    private static final int INITIAL_ERROR_DELAY = 50;
    private static final int MAX_ERROR_DELAY = 1600;

    // ----------------------------------------------------------------- Fields


    /**
     * Running state of the endpoint.
     */
    protected volatile boolean running = false;


    /**
     * Will be set to true whenever the endpoint is paused.
     */
    protected volatile boolean paused = false;

    /**
     * Are we using an internal executor
     */
    protected volatile boolean internalExecutor = false;

    /**
     * counter for nr of connections handled by an endpoint
     */
    private volatile CounterLatch connectionCounterLatch = null;

    /**
     * Socket properties
     */
    protected SocketProperties socketProperties = new SocketProperties();
    public SocketProperties getSocketProperties() {
        return socketProperties;
    }


    // ----------------------------------------------------------------- Properties

    private int maxConnections = 10000;
    public void setMaxConnections(int maxCon) { this.maxConnections = maxCon; }
    public int  getMaxConnections() { return this.maxConnections; }
    /**
     * External Executor based thread pool.
     */
    private Executor executor = null;
    public void setExecutor(Executor executor) {
        this.executor = executor;
        this.internalExecutor = (executor==null);
    }
    public Executor getExecutor() { return executor; }


    /**
     * Server socket port.
     */
    private int port;
    public int getPort() { return port; }
    public void setPort(int port ) { this.port=port; }


    /**
     * Address for the server socket.
     */
    private InetAddress address;
    public InetAddress getAddress() { return address; }
    public void setAddress(InetAddress address) { this.address = address; }

    /**
     * Allows the server developer to specify the backlog that
     * should be used for server sockets. By default, this value
     * is 100.
     */
    private int backlog = 100;
    public void setBacklog(int backlog) { if (backlog > 0) this.backlog = backlog; }
    public int getBacklog() { return backlog; }

    /**
     * Controls when the Endpoint binds the port. <code>true</code>, the default
     * binds the port on {@link #init()} and unbinds it on {@link #destroy()}.
     * If set to <code>false</code> the port is bound on {@link #start()} and
     * unbound on {@link #stop()}.  
     */
    private boolean bindOnInit = true;
    public boolean getBindOnInit() { return bindOnInit; }
    public void setBindOnInit(boolean b) { this.bindOnInit = b; }
    private BindState bindState = BindState.UNBOUND;

    /**
     * Keepalive timeout, if lesser or equal to 0 then soTimeout will be used.
     */
    private int keepAliveTimeout = -1;
    public int getKeepAliveTimeout() { return keepAliveTimeout;}
    public void setKeepAliveTimeout(int keepAliveTimeout) {
        this.keepAliveTimeout = keepAliveTimeout;
    }


    /**
     * Socket TCP no delay.
     */
    public boolean getTcpNoDelay() { return socketProperties.getTcpNoDelay();}
    public void setTcpNoDelay(boolean tcpNoDelay) { socketProperties.setTcpNoDelay(tcpNoDelay); }


    /**
     * Socket linger.
     */
    public int getSoLinger() { return socketProperties.getSoLingerTime(); }
    public void setSoLinger(int soLinger) {
        socketProperties.setSoLingerTime(soLinger);
        socketProperties.setSoLingerOn(soLinger>=0);
    }


    /**
     * Socket timeout.
     */
    public int getSoTimeout() { return socketProperties.getSoTimeout(); }
    public void setSoTimeout(int soTimeout) { socketProperties.setSoTimeout(soTimeout); }

    /**
     * SSL engine.
     */
    private boolean SSLEnabled = false;
    public boolean isSSLEnabled() { return SSLEnabled; }
    public void setSSLEnabled(boolean SSLEnabled) { this.SSLEnabled = SSLEnabled; }


    private int minSpareThreads = 10;
    public int getMinSpareThreads() {
        return Math.min(minSpareThreads,getMaxThreads());
    }
    public void setMinSpareThreads(int minSpareThreads) {
        this.minSpareThreads = minSpareThreads;
        if (running && executor!=null) {
            if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
                ((java.util.concurrent.ThreadPoolExecutor)executor).setCorePoolSize(minSpareThreads);
            } else if (executor instanceof ResizableExecutor) {
                ((ResizableExecutor)executor).resizePool(minSpareThreads, maxThreads);
            }
        }
    }

    /**
     * Maximum amount of worker threads.
     */
    private int maxThreads = 200;
    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
        if (running && executor!=null) {
            if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
                ((java.util.concurrent.ThreadPoolExecutor)executor).setMaximumPoolSize(maxThreads);
            } else if (executor instanceof ResizableExecutor) {
                ((ResizableExecutor)executor).resizePool(minSpareThreads, maxThreads);
            }
        }
    }
    public int getMaxThreads() {
        if (running && executor!=null) {
            if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
                return ((java.util.concurrent.ThreadPoolExecutor)executor).getMaximumPoolSize();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor)executor).getMaxThreads();
            } else {
                return -1;
            }
        } else {
            return maxThreads;
        }
    }

    /**
     * Max keep alive requests
     */
    private int maxKeepAliveRequests=100; // as in Apache HTTPD server
    public int getMaxKeepAliveRequests() {
        return maxKeepAliveRequests;
    }
    public void setMaxKeepAliveRequests(int maxKeepAliveRequests) {
        this.maxKeepAliveRequests = maxKeepAliveRequests;
    }

    /**
     * Name of the thread pool, which will be used for naming child threads.
     */
    private String name = "TP";
    public void setName(String name) { this.name = name; }
    public String getName() { return name; }

    /**
     * The default is true - the created threads will be
     *  in daemon mode. If set to false, the control thread
     *  will not be daemon - and will keep the process alive.
     */
    private boolean daemon = true;
    public void setDaemon(boolean b) { daemon = b; }
    public boolean getDaemon() { return daemon; }


    /**
     * Priority of the worker threads.
     */
    protected int threadPriority = Thread.NORM_PRIORITY;
    public void setThreadPriority(int threadPriority) { this.threadPriority = threadPriority; }
    public int getThreadPriority() { return threadPriority; }

    protected abstract boolean getDeferAccept();


    /**
     * Attributes provide a way for configuration to be passed to sub-components
     * without the {@link org.apache.coyote.ProtocolHandler} being aware of the
     * properties available on those sub-components. One example of such a
     * sub-component is the
     * {@link org.apache.tomcat.util.net.ServerSocketFactory}.
     */
    protected HashMap<String, Object> attributes =
        new HashMap<String, Object>();
    /** 
     * Generic property setter called when a property for which a specific
     * setter already exists within the
     * {@link org.apache.coyote.ProtocolHandler} needs to be made available to
     * sub-components. The specific setter will call this method to populate the
     * attributes.
     */
    public void setAttribute(String name, Object value) {
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("abstractProtocolHandler.setAttribute",
                    name, value));
        }
        attributes.put(name, value);
    }
    /**
     * Used by sub-components to retrieve configuration information.
     */
    public Object getAttribute(String key) {
        Object value = attributes.get(key);
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("abstractProtocolHandler.getAttribute",
                    key, value));
        }
        return value;
    }



    public boolean setProperty(String name, String value) {
        setAttribute(name, value);
        final String socketName = "socket.";
        try {
            if (name.startsWith(socketName)) {
                return IntrospectionUtils.setProperty(socketProperties, name.substring(socketName.length()), value);
            } else {
                return IntrospectionUtils.setProperty(this,name,value,false);
            }
        }catch ( Exception x ) {
            getLog().error("Unable to set attribute \""+name+"\" to \""+value+"\"",x);
            return false;
        }
    }
    public String getProperty(String name) {
        return (String) getAttribute(name);
    }
    
    /**
     * Return the amount of threads that are managed by the pool.
     *
     * @return the amount of threads that are managed by the pool
     */
    public int getCurrentThreadCount() {
        if (executor!=null) {
            if (executor instanceof ThreadPoolExecutor) {
                return ((ThreadPoolExecutor)executor).getPoolSize();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor)executor).getPoolSize();
            } else {
                return -1;
            }
        } else {
            return -2;
        }
    }

    /**
     * Return the amount of threads that are in use
     *
     * @return the amount of threads that are in use
     */
    public int getCurrentThreadsBusy() {
        if (executor!=null) {
            if (executor instanceof ThreadPoolExecutor) {
                return ((ThreadPoolExecutor)executor).getActiveCount();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor)executor).getActiveCount();
            } else {
                return -1;
            }
        } else {
            return -2;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }


    public void createExecutor() {
        internalExecutor = true;
        TaskQueue taskqueue = new TaskQueue();
        TaskThreadFactory tf = new TaskThreadFactory(getName() + "-exec-", daemon, getThreadPriority());
        executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), 60, TimeUnit.SECONDS,taskqueue, tf);
        taskqueue.setParent( (ThreadPoolExecutor) executor);
    }

    public void shutdownExecutor() {
        if ( executor!=null && internalExecutor ) {
            if ( executor instanceof ThreadPoolExecutor ) {
                //this is our internal one, so we need to shut it down
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
                tpe.shutdownNow();
                TaskQueue queue = (TaskQueue) tpe.getQueue();
                queue.setParent(null);
            }
            executor = null;
        }
    }

    /**
     * Unlock the server socket accept using a bogus connection.
     */
    protected void unlockAccept() {
        java.net.Socket s = null;
        InetSocketAddress saddr = null;
        try {
            // Need to create a connection to unlock the accept();
            if (address == null) {
                saddr = new InetSocketAddress("localhost", getPort());
            } else {
                saddr = new InetSocketAddress(address,getPort());
            }
            s = new java.net.Socket();
            int stmo = 2 * 1000;
            int utmo = 2 * 1000;
            if (getSocketProperties().getSoTimeout() > stmo)
                stmo = getSocketProperties().getSoTimeout();
            if (getSocketProperties().getUnlockTimeout() > utmo)
                utmo = getSocketProperties().getUnlockTimeout();
            s.setSoTimeout(stmo);
            // TODO Consider hard-coding to s.setSoLinger(true,0)
            s.setSoLinger(getSocketProperties().getSoLingerOn(),getSocketProperties().getSoLingerTime());
            if (getLog().isDebugEnabled()) {
                getLog().debug("About to unlock socket for:"+saddr);
            }
            s.connect(saddr,utmo);
            if (getDeferAccept()) {
                /*
                 * In the case of a deferred accept / accept filters we need to
                 * send data to wake up the accept. Send OPTIONS * to bypass
                 * even BSD accept filters. The Acceptor will discard it.
                 */
                OutputStreamWriter sw;

                sw = new OutputStreamWriter(s.getOutputStream(), "ISO-8859-1");
                sw.write("OPTIONS * HTTP/1.0\r\n" +
                         "User-Agent: Tomcat wakeup connection\r\n\r\n");
                sw.flush();
            }
            if (getLog().isDebugEnabled()) {
                getLog().debug("Socket unlock completed for:"+saddr);
            }
        } catch(Exception e) {
            if (getLog().isDebugEnabled()) {
                getLog().debug(sm.getString("endpoint.debug.unlock", "" + getPort()), e);
            }
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }


    // ------------------------------------------------------- Lifecycle methods

    /*
     * NOTE: There is no maintenance of state or checking for valid transitions
     * within this class other than ensuring that bind/unbind are called in the
     * right place. It is expected that the calling code will maintain state and
     * prevent invalid state transitions.
     */

    public abstract void bind() throws Exception;
    public abstract void unbind() throws Exception;
    public abstract void startInternal() throws Exception;
    public abstract void stopInternal() throws Exception;

    public final void init() throws Exception {
        if (bindOnInit) {
            bind();
            bindState = BindState.BOUND_ON_INIT;
        }
    }
    
    public final void start() throws Exception {
        if (bindState == BindState.UNBOUND) {
            bind();
            bindState = BindState.BOUND_ON_START;
        }
        startInternal();
    }

    /**
     * Pause the endpoint, which will stop it accepting new connections.
     */
    public void pause() {
        if (running && !paused) {
            paused = true;
            unlockAccept();
            // Heuristic: Sleep for a while to ensure pause of the endpoint
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    /**
     * Resume the endpoint, which will make it start accepting new connections
     * again.
     */
    public void resume() {
        if (running) {
            paused = false;
        }
    }

    public final void stop() throws Exception {
        stopInternal();
        if (bindState == BindState.BOUND_ON_START) {
            unbind();
            bindState = BindState.UNBOUND;
        }
    }

    public final void destroy() throws Exception {
        if (bindState == BindState.BOUND_ON_INIT) {
            unbind();
            bindState = BindState.UNBOUND;
        }
    }


    public String adjustRelativePath(String path, String relativeTo) {
        String newPath = path;
        File f = new File(newPath);
        if ( !f.isAbsolute()) {
            newPath = relativeTo + File.separator + newPath;
            f = new File(newPath);
        }
        if (!f.exists()) {
            getLog().warn("configured file:["+newPath+"] does not exist.");
        }
        return newPath;
    }

    protected abstract Log getLog();
    public abstract boolean getUseSendfile();
    
    protected CounterLatch initializeConnectionLatch() {
        if (connectionCounterLatch==null) {
            connectionCounterLatch = new CounterLatch(0,getMaxConnections());
        }
        return connectionCounterLatch;
    }
    
    protected void releaseConnectionLatch() {
        CounterLatch latch = connectionCounterLatch;
        if (latch!=null) latch.releaseAll();
        connectionCounterLatch = null;
    }
    
    protected void awaitConnection() throws InterruptedException {
        CounterLatch latch = connectionCounterLatch;
        if (latch!=null) latch.await();
    }
    
    protected long countUpConnection() {
        CounterLatch latch = connectionCounterLatch;
        if (latch!=null) return latch.countUp();
        else return -1;
    }
    
    protected long countDownConnection() {
        CounterLatch latch = connectionCounterLatch;
        if (latch!=null) {
            long result = latch.countDown();
            if (result<0) {
                getLog().warn("Incorrect connection count, multiple socket.close called on the same socket." );
            }
            return result;
        } else return -1;
    }
    
    /**
     * Provides a common approach for sub-classes to handle exceptions where a
     * delay is required to prevent a Thread from entering a tight loop which
     * will consume CPU and may also trigger large amounts of logging. For
     * example, this can happen with the Acceptor thread if the ulimit for open
     * files is reached.
     * 
     * @param currentErrorDelay The current delay beign applied on failure
     * @return  The delay to apply on the next failure
     */
    protected int handleExceptionWithDelay(int currentErrorDelay) {
        // Don't delay on first exception
        if (currentErrorDelay > 0) {
            try {
                Thread.sleep(currentErrorDelay);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        
        // On subsequent exceptions, start the delay at 50ms, doubling the delay
        // on every subsequent exception until the delay reaches 1.6 seconds.
        if (currentErrorDelay == 0) {
            return INITIAL_ERROR_DELAY;
        } else if (currentErrorDelay < MAX_ERROR_DELAY) {
            return currentErrorDelay * 2;
        } else {
            return MAX_ERROR_DELAY;
        }

    }

    // --------------------  SSL related properties --------------------

    private String algorithm = KeyManagerFactory.getDefaultAlgorithm();
    public String getAlgorithm() { return algorithm;}
    public void setAlgorithm(String s ) { this.algorithm = s;}

    private String clientAuth = "false";
    public String getClientAuth() { return clientAuth;}
    public void setClientAuth(String s ) { this.clientAuth = s;}

    private String keystoreFile = System.getProperty("user.home")+"/.keystore";
    public String getKeystoreFile() { return keystoreFile;}
    public void setKeystoreFile(String s ) {
        String file = adjustRelativePath(s,
                System.getProperty(Constants.CATALINA_BASE_PROP));
        this.keystoreFile = file;
    }

    private String keystorePass = null;
    public String getKeystorePass() { return keystorePass;}
    public void setKeystorePass(String s ) { this.keystorePass = s;}

    private String keystoreType = "JKS";
    public String getKeystoreType() { return keystoreType;}
    public void setKeystoreType(String s ) { this.keystoreType = s;}

    private String keystoreProvider = null;
    public String getKeystoreProvider() { return keystoreProvider;}
    public void setKeystoreProvider(String s ) { this.keystoreProvider = s;}

    private String sslProtocol = "TLS";
    public String getSslProtocol() { return sslProtocol;}
    public void setSslProtocol(String s) { sslProtocol = s;}

    // Note: Some implementations use the comma separated string, some use
    // the array
    private String ciphers = null;
    private String[] ciphersarr = new String[0];
    public String[] getCiphersArray() { return this.ciphersarr;}
    public String getCiphers() { return ciphers;}
    public void setCiphers(String s) {
        ciphers = s;
        if ( s == null ) ciphersarr = new String[0];
        else {
            StringTokenizer t = new StringTokenizer(s,",");
            ciphersarr = new String[t.countTokens()];
            for (int i=0; i<ciphersarr.length; i++ ) ciphersarr[i] = t.nextToken();
        }
    }

    private String keyAlias = null;
    public String getKeyAlias() { return keyAlias;}
    public void setKeyAlias(String s ) { keyAlias = s;}

    private String keyPass = JSSESocketFactory.DEFAULT_KEY_PASS;
    public String getKeyPass() { return keyPass;}
    public void setKeyPass(String s ) { this.keyPass = s;}

    private String truststoreFile = System.getProperty("javax.net.ssl.trustStore");
    public String getTruststoreFile() {return truststoreFile;}
    public void setTruststoreFile(String s) {
        String file = adjustRelativePath(s,
                System.getProperty(Constants.CATALINA_BASE_PROP));
        this.truststoreFile = file;
    }

    private String truststorePass =
        System.getProperty("javax.net.ssl.trustStorePassword");
    public String getTruststorePass() {return truststorePass;}
    public void setTruststorePass(String truststorePass) {
        this.truststorePass = truststorePass;
    }

    private String truststoreType =
        System.getProperty("javax.net.ssl.trustStoreType");
    public String getTruststoreType() {return truststoreType;}
    public void setTruststoreType(String truststoreType) {
        this.truststoreType = truststoreType;
    }

    private String truststoreProvider = null;
    public String getTruststoreProvider() {return truststoreProvider;}
    public void setTruststoreProvider(String truststoreProvider) {
        this.truststoreProvider = truststoreProvider;
    }

    private String truststoreAlgorithm = null;
    public String getTruststoreAlgorithm() {return truststoreAlgorithm;}
    public void setTruststoreAlgorithm(String truststoreAlgorithm) {
        this.truststoreAlgorithm = truststoreAlgorithm;
    }

    private String crlFile = null;
    public String getCrlFile() {return crlFile;}
    public void setCrlFile(String crlFile) {
        this.crlFile = crlFile;
    }

    private String trustMaxCertLength = null;
    public String getTrustMaxCertLength() {return trustMaxCertLength;}
    public void setTrustMaxCertLength(String trustMaxCertLength) {
        this.trustMaxCertLength = trustMaxCertLength;
    }

    private String sessionCacheSize = null;
    public String getSessionCacheSize() { return sessionCacheSize;}
    public void setSessionCacheSize(String s) { sessionCacheSize = s;}

    private String sessionTimeout = "86400";
    public String getSessionTimeout() { return sessionTimeout;}
    public void setSessionTimeout(String s) { sessionTimeout = s;}

    private String allowUnsafeLegacyRenegotiation = null;
    public String getAllowUnsafeLegacyRenegotiation() {
        return allowUnsafeLegacyRenegotiation;
    }
    public void setAllowUnsafeLegacyRenegotiation(String s) {
        allowUnsafeLegacyRenegotiation = s;
    }


    private String[] sslEnabledProtocolsarr =  new String[0];
    public String[] getSslEnabledProtocolsArray() {
        return this.sslEnabledProtocolsarr;
    }
    public void setSslEnabledProtocols(String s) {
        if (s == null) {
            this.sslEnabledProtocolsarr = new String[0];
        } else {
            ArrayList<String> sslEnabledProtocols = new ArrayList<String>();
            StringTokenizer t = new StringTokenizer(s,",");
            while (t.hasMoreTokens()) {
                String p = t.nextToken().trim();
                if (p.length() > 0) {
                    sslEnabledProtocols.add(p);
                }
            }
            sslEnabledProtocolsarr = sslEnabledProtocols.toArray(
                    new String[sslEnabledProtocols.size()]);
        }
    }

}

