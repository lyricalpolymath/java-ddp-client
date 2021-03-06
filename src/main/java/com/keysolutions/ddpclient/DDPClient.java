/*
* (c)Copyright 2013-2014 Ken Yee, KEY Enterprise Solutions 
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.keysolutions.ddpclient;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Observable;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket.READYSTATE;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.Gson;



/**
 * Java Meteor DDP websocket client
 * @author kenyee
 */
public class DDPClient extends Observable {
    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this.getClass());

    /** Field names supported in the DDP protocol */
    public class DdpMessageField {
        public static final String MSG = "msg";
        public static final String ID = "id";
        public static final String METHOD = "method";
        public static final String METHODS = "methods";
        public static final String SUBS = "subs";
        public static final String PARAMS = "params";
        public static final String RESULT = "result";
        public static final String NAME = "name";
        public static final String SERVER_ID = "server_id";
        public static final String ERROR = "error";
        public static final String SESSION = "session";
        public static final String VERSION = "version";
        public static final String SUPPORT = "support";
        public static final String SOURCE = "source";
        public static final String ERRORMSG = "errormsg";
        public static final String CODE = "code";
        public static final String REASON = "reason";
        public static final String REMOTE = "remote";
        public static final String COLLECTION = "collection";
        public static final String FIELDS = "fields";
        public static final String CLEARED = "cleared";
    }

    /** Message types supported in the DDP protocol */
    public class DdpMessageType {
        // client -> server
        public static final String CONNECT = "connect";
        public static final String METHOD = "method";
        // server -> client
        public static final String CONNECTED = "connected";
        public static final String UPDATED = "updated";
        public static final String READY = "ready";
        public static final String NOSUB = "nosub";
        public static final String RESULT = "result";
        public static final String SUB = "sub";
        public static final String UNSUB = "unsub";
        public static final String ERROR = "error";
        public static final String CLOSED = "closed";
        public static final String ADDED = "added";

        public static final String REMOVED = "removed";
        public static final String CHANGED = "changed";
        // BB-MOD -added for DDP Protocol v1 - https://github.com/meteor/meteor/blob/devel/packages/ddp/DDP.md
        public static final String PING = "ping";
        public static final String PONG = "pong";
        // TODO addedbefore and movedbefore are used with ordered collections - but as of Meteor 1.0 those are not used
        // The ordered collection DDP messages are not currently used by Meteor. They will likely be used by Meteor in the future.
        public static final String ADDEDBEFORE = "addedbefore"; // Todo find a usage for it in DDPStateSingleton
        public static final String MOVEDBEFORE = "movedbefore"; // Todo find a usage for it in DDPStateSingleton
    }

    /** DDP protocol version */
    private final static String DDP_PROTOCOL_VERSION = "1";//"pre1"; // BB-MOD updated from "pre1" to "1"
    /** DDP connection state */
    public enum CONNSTATE {
        Disconnected,
        Connected,
        Closed,
    };
    private CONNSTATE mConnState;
    /** current command ID */
    private int mCurrentId;
    /** callback tracking for DDP commands */
    private Map<String, DDPListener> mMsgListeners;
    /** web socket client */
    private WebSocketClient mWsClient;
    /** web socket address for reconnections */
    private String mMeteorServerAddress;
    /** we can't connect more than once on a new socket */
    private boolean mConnectionStarted;
    /** Google GSON object */
    private final Gson mGson = new Gson();

    /**
     * Instantiates a Meteor DDP client for the Meteor server located at the
     * supplied IP and port (note: running Meteor locally will typically have a
     * port of 3000 but port 80 is the typical default for publicly deployed
     * servers)
     * 
     * @param meteorServerIp IP of Meteor server
     * @param meteorServerPort Port of Meteor server, if left null it will default to 3000
     * @param useSSL Whether to use SSL for websocket encryption
     * @throws URISyntaxException
     */
    public DDPClient(String meteorServerIp, Integer meteorServerPort, boolean useSSL) throws URISyntaxException {
        log.info("DDPClient", "DDPClient - " + "meteorServerIp = [" + meteorServerIp + "], meteorServerPort = [" + meteorServerPort + "], useSSL = [" + useSSL + "]");

        initWebsocket(meteorServerIp, meteorServerPort, useSSL);
    }
    
    /**
     * Instantiates a Meteor DDP client for the Meteor server located at the
     * supplied IP and port (note: running Meteor locally will typically have a
     * port of 3000 but port 80 is the typical default for publicly deployed
     * servers)
     * 
     * @param meteorServerIp
     *            - IP of Meteor server
     * @param meteorServerPort
     *            - Port of Meteor server, if left null it will default to 3000
     * @throws URISyntaxException
     */
    public DDPClient(String meteorServerIp, Integer meteorServerPort) throws URISyntaxException {
        initWebsocket(meteorServerIp, meteorServerPort, false);
    }
    
    /**
     * Initializes a websocket connection
     * @param meteorServerIp IP address of Meteor server
     * @param meteorServerPort port of Meteor server, if left null it will default to 3000
     * @param useSSL whether to use SSL
     * @throws URISyntaxException
     */
    private void initWebsocket(String meteorServerIp, Integer meteorServerPort, boolean useSSL)
            throws URISyntaxException {
        mConnState = CONNSTATE.Disconnected;
        if (meteorServerPort == null)
            meteorServerPort = 3000;
        mMeteorServerAddress = (useSSL ? "wss://" : "ws://")
                + meteorServerIp + ":"
                + meteorServerPort.toString() + "/websocket";
        this.mCurrentId = 0;
        this.mMsgListeners = new ConcurrentHashMap<String, DDPListener>();
        createWsClient(mMeteorServerAddress);
    }
    
    /**
     * Creates a web socket client
     * @param meteorServerAddress Websocket address of Meteor server
     * @throws URISyntaxException
     */
    public void createWsClient(String meteorServerAddress)
            throws URISyntaxException {
        this.mWsClient = new WebSocketClient(new URI(meteorServerAddress)) {

            @Override
            public void onOpen(ServerHandshake handshakedata) {
                connectionOpened();
            }

            @Override
            public void onMessage(String message) {
                received(message);
            }

            @Override
            public void onError(Exception ex) {
                handleError(ex);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                connectionClosed(code, reason, remote);
            }
        };
        mConnectionStarted = false;
    }

    /**
     * Called after initial web-socket connection. Sends back a connection
     * confirmation message to the Meteor server.
     */
    private void connectionOpened() {
        log.trace("DDPClient - WebSocket connection opened sending protocol version: " + DDP_PROTOCOL_VERSION);
        // reply to Meteor server with connection confirmation message ({"msg":
        // "connect"})

        // Builds an object appropriate for the ddp protocol
        // {"msg":"connect","version":"1","support":["pre1"]}
        // but it could be
        // {"msg":"connect","version":"1","support":["1","pre2","pre1"]}
        Map<String, Object> connectMsg = new HashMap<String, Object>();
        connectMsg.put(DdpMessageField.MSG, DdpMessageType.CONNECT);
        connectMsg.put(DdpMessageField.VERSION, DDP_PROTOCOL_VERSION);
        connectMsg.put(DdpMessageField.SUPPORT,
                new String[] { DDP_PROTOCOL_VERSION });
        send(connectMsg);
        // we'll get a msg:connected from the Meteor server w/ a session ID when we connect
        // note that this may return an error that the DDP protocol isn't correct
    }

    /**
     * Called when connection is closed
     * 
     * @param code WebSocket Error code
     * @param reason Reason msg for error
     * @param remote Whether error is from remote side
     */
    private void connectionClosed(int code, String reason, boolean remote) {
        // changed formatting to always return a JSON object
        String closeMsg = "{\"msg\":\"closed\",\"code\":\"" + code
                + "\",\"reason\":\"" + reason + "\",\"remote\":" + remote + "}";
        log.debug("{}", closeMsg);
        received(closeMsg);
    }

    /**
     * Error handling for any errors over the web-socket connection
     * 
     * @param ex exception to convert to event
     */
    private void handleError(Exception ex) {
        // changed formatting to always return a JSON object
        String errmsg = ex.getMessage();
        if (errmsg == null) {
            errmsg = "Unknown websocket error (exception in callback?)";
        }
        String errorMsg = "{\"msg\":\"error\",\"source\":\"WebSocketClient\",\"errormsg\":\""
                + errmsg + "\"}";
        log.debug("{}", errorMsg);
        // ex.printStackTrace();
        received(errorMsg);
    }

    /**
     * Increments and returns the client's current ID
     * 
     * @note increment/decrement/set on int (but not long) are atomic on the JVM
     * @return integer DDP call ID
     */
    private int nextId() {
        return ++mCurrentId;
    }

    /**
     * Registers a client DDP command results callback listener
     * 
     * @param DDP command results callback
     * @return ID for next command
     */
    private int addCommmand(DDPListener resultListener) {
        int id = nextId();
        if (resultListener != null) {
            // store listener for callbacks
            mMsgListeners.put(Integer.toString(id), resultListener);
        }
        return id;
    }

    /**
     * Initiate connection to meteor server
     */
    public void connect() {
        log.info("DDPClient - connect info");
        if (this.mWsClient.getReadyState() == READYSTATE.CLOSED) {
            // we need to create a new wsClient because a closed websocket cannot be reused
            try {
                createWsClient(mMeteorServerAddress);
            } catch (URISyntaxException e) {
                // we shouldn't get URI exceptions because the address was validated in initWebsocket
            }
        }
        if (!mConnectionStarted) {
            // only do the connect if no connection attempt has been done for this websocket client
            this.mWsClient.connect();
            mConnectionStarted = true;
        }
    }
    
    /**
     * Closes an open websocket connection.
     * This is async, so you'll get a close notification callback when it eventually closes.
     */
    public void disconnect() {
        if (this.mWsClient.getReadyState() == READYSTATE.OPEN) {
            this.mWsClient.close();
        }
    }

    /**
     * Call a meteor method with the supplied parameters
     * 
     * @param method name of corresponding Meteor method
     * @param params arguments to be passed to the Meteor method
     * @param resultListener DDP command listener for this method call
     */
    public int call(String method, Object[] params, DDPListener resultListener) {
        Map<String, Object> callMsg = new HashMap<String, Object>();
        callMsg.put(DdpMessageField.MSG, DdpMessageType.METHOD);
        callMsg.put(DdpMessageField.METHOD, method);
        callMsg.put(DdpMessageField.PARAMS, params);

        int id = addCommmand(resultListener/*
                                            * "method,"+method+","+Arrays.toString
                                            * (params)
                                            */);
        callMsg.put(DdpMessageField.ID, Integer.toString(id));
        send(callMsg);
        return id;
    }

    /**
     * Call a meteor method with the supplied parameters
     * 
     * @param method name of corresponding Meteor method
     * @param params arguments to be passed to the Meteor method
     */
    public int call(String method, Object[] params) {
        return call(method, params, null);
    }

    /**
     * Subscribe to a Meteor record set with the supplied parameters
     * 
     * @param name name of the corresponding Meteor subscription
     * @param params arguments corresponding to the Meteor subscription
     * @param resultListener DDP command listener for this call
     */
    public int subscribe(String name, Object[] params, DDPListener resultListener) {
        Map<String, Object> subMsg = new HashMap<String, Object>();
        subMsg.put(DdpMessageField.MSG, DdpMessageType.SUB);
        subMsg.put(DdpMessageField.NAME, name);
        subMsg.put(DdpMessageField.PARAMS, params);

        int id = addCommmand(resultListener/*
                                            * "sub,"+name+","+Arrays.toString(params
                                            * )
                                            */);
        subMsg.put(DdpMessageField.ID, Integer.toString(id));
        send(subMsg);
        return id;
    }

    /**
     * Subscribe to a Meteor record set with the supplied parameters
     * 
     * @param name name of the corresponding Meteor subscription
     * @param params arguments corresponding to the Meteor subscription
     */
    public int subscribe(String name, Object[] params) {
        return subscribe(name, params, null);
    }

    /**
     * Unsubscribe from a Meteor record set
     * 
     * @param name name of the corresponding Meteor subscription
     * @param resultListener DDP command listener for this call
     */
    public int unsubscribe(String name, DDPListener resultListener) {
        Map<String, Object> unsubMsg = new HashMap<String, Object>();
        unsubMsg.put(DdpMessageField.MSG, DdpMessageType.UNSUB);
        unsubMsg.put(DdpMessageField.NAME, name);

        int id = addCommmand(resultListener/* "unsub,"+name */);
        unsubMsg.put(DdpMessageField.ID, Integer.toString(id));
        send(unsubMsg);
        return id;
    }

    /**
     * Unsubscribe from a Meteor record set
     * 
     * @param name
     *            - name of the corresponding Meteor subscription
     */
    public int unsubscribe(String name) {
        return unsubscribe(name, null);
    }

    /**
     * Inserts document into collection from the client
     * 
     * @param collectionName Name of collection
     * @param insertParams Document fields
     * @param resultListener DDP command listener for this call
     * @return Returns command ID
     */
    public int collectionInsert(String collectionName,
            Map<String, Object> insertParams, DDPListener resultListener) {
        Object[] collArgs = new Object[1];
        collArgs[0] = insertParams;
        return call("/" + collectionName + "/insert", collArgs);
    }

    /**
     * Inserts document into collection from client-side
     * 
     * @param collectionName Name of collection
     * @param insertParams Document fields
     * @return Returns command ID
     */
    public int collectionInsert(String collectionName,
            Map<String, Object> insertParams) {
        return collectionInsert(collectionName, insertParams, null);
    }

    /**
     * Deletes collection document from the client
     * 
     * @param collectionName Name of collection
     * @param docId _id of document
     * @param resultListener Callback handler for command results
     * @return Returns command ID
     */
    public int collectionDelete(String collectionName, String docId,
            DDPListener resultListener) {
        Object[] collArgs = new Object[1];
        Map<String, Object> selector = new HashMap<String, Object>();
        selector.put("_id", docId);
        collArgs[0] = selector;
        return call("/" + collectionName + "/remove", collArgs);
    }

    public int collectionDelete(String collectionName, String docId) {
        return collectionDelete(collectionName, docId, null);
    }

    /**
     * Updates a collection document from the client NOTE: for security reasons,
     * you can only do this one document at a time.
     * 
     * @param collectionName
     *            Name of collection
     * @param docId _id of document
     * @param updateParams Map w/ mongoDB parameters to pass in for update
     * @param resultListener Callback handler for command results
     * @return Returns command ID
     */
    public int collectionUpdate(String collectionName, String docId,
            Map<String, Object> updateParams, DDPListener resultListener) {
        Map<String, Object> selector = new HashMap<String, Object>();
        Object[] collArgs = new Object[2];
        selector.put("_id", docId);
        collArgs[0] = selector;
        collArgs[1] = updateParams;
        return call("/" + collectionName + "/update", collArgs);
    }

    /**
     * Updates a collection document from the client NOTE: for security reasons,
     * you can only do this one document at a time.
     * 
     * @param collectionName Name of collection
     * @param docId _id of document
     * @param updateParams Map w/ mongoDB parameters to pass in for update
     * @return Returns command ID
     */
    public int collectionUpdate(String collectionName, String docId,
            Map<String, Object> updateParams) {
        return collectionUpdate(collectionName, docId, updateParams, null);
    }

    /**
     * Converts DDP-formatted message to JSON and sends over web-socket
     * 
     * @param connectMsg
     */
    public void send(Map<String, Object> connectMsg) {
        String json = mGson.toJson(connectMsg);
        /*System.out.println*/log.info("DDPClient - Sending DDP message: {} " , json);
        try {
        this.mWsClient.send(json);
        } catch (WebsocketNotConnectedException ex) {
            handleError(ex);
            mConnState = CONNSTATE.Closed;
        }
    }

    /**
     * Notifies observers of this DDP client of messages received from the
     * Meteor server
     * read DDP Specification: https://github.com/meteor/meteor/blob/devel/packages/ddp/DDP.md
     * 
     * @param msg received msg from websocket
     */
    @SuppressWarnings("unchecked")
    public void received(String msg) {

         /*System.out.println*/log.info("DDPClient - Received DDP message: {}", msg);
        this.setChanged();
        // generic object deserialization is from
        // http://programmerbruce.blogspot.com/2011/06/gson-v-jackson.html
        Map<String, Object> jsonFields = mGson.fromJson((String) msg, HashMap.class);
        //this.notifyObservers(jsonFields); //BB-MOD moved at the end of the if else clauses so that the observers are notified AFTER the internal state has been updated

        // notify any command listeners if we get updated or result msgs
        String msgtype = (String) jsonFields.get(DdpMessageField.MSG.toString());
        //log.info("DDPClient - Received msgtype: " + msgtype);
        if (msgtype == null) {
            // ignore {"server_id":"GqrKrbcSeDfTYDkzQ"} web socket msgs
            return;
        }
        if (msgtype.equals(DdpMessageType.UPDATED)) {
            ArrayList<String> methodIds = (ArrayList<String>) jsonFields.get(DdpMessageField.METHODS);
            for (String methodId : methodIds) {
                DDPListener listener = (DDPListener) mMsgListeners.get(methodId);
                if (listener != null) {
                    listener.onUpdated(methodId);
                }
            }
        } else if (msgtype.equals(DdpMessageType.READY)) {
            ArrayList<String> methodIds = (ArrayList<String>) jsonFields.get(DdpMessageField.SUBS);
            for (String methodId : methodIds) {
                DDPListener listener = (DDPListener) mMsgListeners.get(methodId);
                if (listener != null) {
                    listener.onReady(methodId);
                }
            }
        } else if (msgtype.equals(DdpMessageType.NOSUB)) {
            String msgId = (String) jsonFields.get(DdpMessageField.ID.toString());
            DDPListener listener = (DDPListener) mMsgListeners.get(msgId);
            if (listener != null) {
                listener.onNoSub(msgId, (Map<String, Object>) jsonFields.get(DdpMessageField.ERROR));
                mMsgListeners.remove(msgId);
            }
        } else if (msgtype.equals(DdpMessageType.RESULT)) {
            String msgId = (String) jsonFields.get(DdpMessageField.ID.toString());
            if (msgId != null) {
                DDPListener listener = (DDPListener) mMsgListeners.get(msgId);
                if (listener != null) {
                    listener.onResult(jsonFields);
                    mMsgListeners.remove(msgId);
                }
            }
        } else if (msgtype.equals(DdpMessageType.CONNECTED)) {
            mConnState = CONNSTATE.Connected;
        }
        else if (msgtype.equals(DdpMessageType.CLOSED)) {
            mConnState = CONNSTATE.Closed;
        }

        //BB-MOD (the server sends ping I should answer PONG -- if I send PING > I should test for PONG or reconnect
        else if (msgtype.equals(DdpMessageType.PING)) {
            //reply with PONG
            Map<String, Object> pingMsg = new HashMap<String, Object>();
            pingMsg.put(DdpMessageField.MSG, DdpMessageType.PONG);
            //  If the received ping message includes an id field, the pong message must include the same id field.
            String msgId = (String) jsonFields.get(DdpMessageField.ID.toString());
            if (msgId != null) {
                pingMsg.put(DdpMessageField.ID, msgId);
            }
            send(pingMsg);
        }
        else if (msgtype.equals(DdpMessageType.PONG)) {
            //TODO - DDPClient - Server is still alive so stop the countdown timer to reconnection attempt
        }

        //BB-MOD - moved here so that the observers are notified AFTER the internal state has been updated
        this.notifyObservers(jsonFields);
    }

    /**
     * @return current DDP connection state (disconnected/connected/closed)
     */
    public CONNSTATE getState() {
        return mConnState;
    }
}
