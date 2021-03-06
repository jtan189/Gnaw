package com.gnaw;

import java.io.File;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.UUID;

import javax.swing.DefaultListModel;

import com.gnaw.chord.ChordCallbackImpl;
import com.gnaw.chord.FilenameKey;
import com.gnaw.chord.SharedFileEntry;
import com.gnaw.discovery.BeaconClient;
import com.gnaw.discovery.BeaconServer;
import com.gnaw.discovery.event.BroadcastingEndEventListener;
import com.gnaw.discovery.event.ClientFoundEventListener;
import com.gnaw.interfaces.DataSourceInterface;
import com.gnaw.interfaces.TransmissionProgressInterface;
import com.gnaw.models.SharedFile;
import com.gnaw.request.Request;
import com.gnaw.request.Request.Action;
import com.gnaw.request.Request.RequestIdentifier;
import com.gnaw.response.Response;
import com.gnaw.transmission.TransmissionClient;
import com.gnaw.transmission.TransmissionServer;

import de.uniba.wiai.lspi.chord.data.URL;
import de.uniba.wiai.lspi.chord.service.AsynChord;
import de.uniba.wiai.lspi.chord.service.PropertiesLoader;
import de.uniba.wiai.lspi.chord.service.ServiceException;
import de.uniba.wiai.lspi.chord.service.impl.ChordImpl;

/**
 * 
 * @author Cesar Ramirez, Josh Tan
 */
public class GnawApplication {

	public final static String DEFAULT_MASTER_HOST = "localhost";
	public final static int DEFAULT_MASTER_PORT = 8080;

	private BeaconServer beaconServer;
	private BeaconClient beaconClient;
	private TransmissionServer transmissionServer;
	private TransmissionClient transmissionClient;
	private DataSourceInterface source;
	private HashMap<String, String> sendRequests;
	private HashMap<String, String> receiveRequests;

	private AsynChord chord;
	private String hostAddress;
	private int hostPort;

	/**
	 * Default constructor.
	 * @param source data source for the application
	 */
	public GnawApplication(DataSourceInterface source) {
		this(source, false, DEFAULT_MASTER_HOST, DEFAULT_MASTER_PORT);
	}

	public GnawApplication(DataSourceInterface source, boolean isMaster) {
		this(source, isMaster, DEFAULT_MASTER_HOST, DEFAULT_MASTER_PORT);
	}

	/**
	 * Overloaded constructor.
	 * @param source data source for the application
	 * @param isMaster true, if this is a master node
	 * @param host host of node to bootstrap to (ignored, if is a master node)
	 * @param port if is a master node, port to bind to; otherwise, port of node to bootstrap to
	 */
	public GnawApplication(DataSourceInterface source, boolean isMaster, String host, int port) {
		this.source = source;
		this.sendRequests = new HashMap<String, String>();
		this.receiveRequests = new HashMap<String, String>();

		this.beaconServer = new BeaconServer();
		this.beaconClient = new BeaconClient();
		this.transmissionClient = new TransmissionClient();
		this.transmissionServer = new TransmissionServer(this.source);
		this.transmissionServer.startListening();
		
		// initialize Chord network
		PropertiesLoader.loadPropertyFile();
	}

	public void createChordNetwork() {
		createChordNetwork(getProtocol(),  DEFAULT_MASTER_PORT);
	}

	public String getProtocol(){
		return URL.KNOWN_PROTOCOLS.get(URL.SOCKET_PROTOCOL);
	}
	
	/**
	 * Create a new Chord network.
	 * @param protocol network protocol to use
	 * @param port port to use for the master node
	 */
	public void createChordNetwork(String protocol, int port) {

		String localUrlString;
		URL localUrl = null;		

		try {
			hostAddress = getHostAddress();
			hostPort = port;
			localUrlString = createUrl(protocol, hostAddress, hostPort);
			
		} catch (UnknownHostException e) {
			throw new RuntimeException (e);
		} catch (Exception e) {
			System.err.println(e.getMessage());
			throw new RuntimeException("Error create local URL string!", e);
		}

		try {
			localUrl = new URL(localUrlString);
		} catch (MalformedURLException e) {
			throw new RuntimeException("Incorrect Url: " + localUrlString, e);
		}

		chord = new ChordImpl();
		
		try {
			chord.create(localUrl);
		} catch (ServiceException e) {
			throw new RuntimeException("Could not create DHT !", e);
		}

	}

	public void stopChordNetwork() {
		try {
			chord.leave();
		} catch (ServiceException e) {
			throw new RuntimeException("Error while leaving the network!", e);
		}
	}
	
	/**
	 * Join an existing Chord network.
	 * @param protocol network protocol to use
	 * @param bootstrapHost host of the node to bootstrap to
	 * @param bootstrapPort port of the node to bootstrap to
	 */
	public void joinChordNetwork(String protocol, String bootstrapHost, int bootstrapPort) {

		try {
			hostPort = getPort();
		} catch (IOException e) {
			throw new RuntimeException("Error while trying to find free port!", e);
		}

		String localUrlString;
		URL localUrl = null;

		try {
			hostAddress = getHostAddress();
			localUrlString = createUrl(protocol, hostAddress, hostPort);
		} catch (Exception e) {
			System.err.println(e.getMessage());
			throw new RuntimeException("Error create local URL string!", e);
		}

		try {
			localUrl = new URL(localUrlString);
		} catch (MalformedURLException e) {
			throw new RuntimeException (e) ;
		}

		String bootstrapUrlString = createUrl(protocol, bootstrapHost,	bootstrapPort);
		URL bootstrapUrl = null;
		try {
			bootstrapUrl = new URL(bootstrapUrlString);
		} catch (MalformedURLException e) {
			throw new RuntimeException("Incorrect Url: " + bootstrapUrlString, e);
		}

		chord = new ChordImpl();
		try {
			chord.join(localUrl, bootstrapUrl);	
			
		} catch (ServiceException e) {
			throw new RuntimeException("Could not create DHT !", e);
		}

	}
	
	public void shareFile(String filename) {

		ChordCallbackImpl callback = new ChordCallbackImpl();

		// create key
		FilenameKey key = new FilenameKey(filename);
		
		// create entry
		SharedFileEntry entry = new SharedFileEntry(filename, hostAddress, hostPort);
		
		// insert (key, entry)
		chord.insert(key, entry, callback);
	}

	public void unshareFile(String filename) {

		ChordCallbackImpl callback = new ChordCallbackImpl();

		// create key
		FilenameKey key = new FilenameKey(filename);
		
		// create entry
		SharedFileEntry entry = new SharedFileEntry(filename, hostAddress, hostPort);
		
		// remove (key, entry)
		chord.remove(key, entry, callback);
	}

	public boolean startBroadcasting(BroadcastingEndEventListener listener, int seconds) {
		try {
			this.beaconServer.startBroadcasting(seconds);
			this.beaconServer.addBroadcastingEndEventListener(listener);
			return true;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return false;
	}

	public boolean stopBroadcasting() {
		try {
			this.beaconServer.stopBroadcasting();
			return true;
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return false;
	}

	public boolean startListening(ClientFoundEventListener listener) {
		try {
			this.beaconClient.startListening();
			this.beaconClient.addClientFoundEventListener(listener);
			return true;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return false;
	}

	public boolean stopListening() {
		try {
			this.beaconClient.stopListening();
			return true;
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return false;
	}

	public boolean searchFile(String term, DefaultListModel textArea) {
		
		FilenameKey key;
		ChordCallbackImpl callback = new ChordCallbackImpl(textArea);

		key = new FilenameKey(term);
		chord.retrieve(key, callback);

		return true;
	}

	public Response requestProfile(String address) {
		Request profileRequest = new Request(RequestIdentifier.GET_PROFILE, this.source.getProfile().getName());
		return this.transmissionClient.startConnection(address, profileRequest);
	}

	public Response requestSharedFiles(String address) {
		Request filesRequest = new Request(RequestIdentifier.GET_SHARED_FILES, this.source.getProfile().getName());
		return this.transmissionClient.startConnection(address, filesRequest);
	}

	public Response sendMessage(String address) {
		// TODO Auto-generated method stub
		return null;
	}

	public Response sendOffer(String address, SharedFile file) {
		Request fileOffer = new Request(RequestIdentifier.OFFER, this.source.getProfile().getName());
		fileOffer.setFileName(file.getName());
		String uuid = UUID.randomUUID().toString();
		fileOffer.setToken(uuid);
		Response response = this.transmissionClient.startConnection(address, fileOffer);
		this.sendRequests.put(uuid, file.getPath());
		return response;
	}

	public Response sendOfferResponse(String address, boolean accept, String filename, String token) {
		Request offerResponse = new Request(RequestIdentifier.RESPONSE, this.source.getProfile().getName());
		if (accept) {
			offerResponse.setAction(Action.ACCEPT);
			offerResponse.setToken(token);
			this.receiveRequests.put(token, filename);
		} else {
			offerResponse.setAction(Action.REJECT);
		}
		return this.transmissionClient.startConnection(address, offerResponse);
	}

	public Response sendSearchRequest(String address) {
		// TODO Auto-generated method stub
		return null;
	}

	public Response sendSearchResult(String address) {
		// TODO Auto-generated method stub
		return null;
	}

	public Response sendPushRequest(String address, String token, TransmissionProgressInterface listener) {
		Request pushResponse = new Request(RequestIdentifier.PUSH, this.source.getProfile().getName());
		String filename = this.sendRequests.get(token);
		File file = new File(filename);
		pushResponse.setFileSize(file.length());
		pushResponse.setFileName(file.getName());
		this.transmissionClient.setListener(listener);
		return this.transmissionClient.startConnection(address, pushResponse, filename);
	}

	public void saveSettings(String key, String value) {
		Settings sett = new Settings();
		sett.open();
		sett.setValue(key, value);
		sett.close();
	}

	public String retrieveSettings(String key) {
		Settings sett = new Settings();
		sett.open();
		String result = sett.getValue(key);
		sett.close();
		return result;
	}

	/**
	 * Return an unused port (automatically allocated).
	 * @return unused port number
	 * @throws IOException
	 */
	private int getPort() throws IOException {
		ServerSocket server = new ServerSocket(0);
		int localPort = server.getLocalPort();
		server.close();
		return localPort;
	}

	/**
	 * Helper method for creating a URL string.
	 * @param protocol network protocol
	 * @param host URL host
	 * @param port URL port
	 * @return a URL string
	 */
	private String createUrl(String protocol, String host, int port) {
		return protocol + "://" + host + ':' + port + '/';
	}
	
	/**
	 * Retrieve the IP address of the host. In the case the host OS is not Linux,
	 * InetAddress.getHostAddress() is simply returned. If the host OS is Linux,
	 * additional steps are performed to avoid returning the loopback address
	 * (e.g. 127.0.0.1).
	 * 
	 * Source:
	 * http://sourceforge.net/p/open-chord/bugs/2/
	 * 
	 * @return the IP address of the host
	 * @throws UnknownHostException
	 * @throws SocketException
	 */
	private String getHostAddress() throws UnknownHostException, SocketException {

		if (System.getProperty("os.name").equals("Linux")) {
			Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
			while (e.hasMoreElements()) {
				NetworkInterface netface = (NetworkInterface) e.nextElement();
				if (!netface.getName().equals("lo")) { 
					Enumeration<InetAddress> e2 = netface.getInetAddresses();
					while (e2.hasMoreElements()) {
						InetAddress ip = (InetAddress) e2.nextElement();
						if (!(ip instanceof Inet6Address)) {
							return ip.toString().substring(1);
						}
//						e2.nextElement();
//						InetAddress ip = (InetAddress) e2.nextElement();
//						return ip.toString().substring(1);
					}
				}
			}
			
			throw new UnknownHostException("Unable to retrieve IP address for Linux host.");
			
		} else {
			return java.net.InetAddress.getLocalHost().getHostAddress();
		}
	}
}
