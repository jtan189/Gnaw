package com.gnaw.discovery;

import com.gnaw.discovery.event.BroadcastingEndEvent;
import com.gnaw.discovery.event.BroadcastingEndEventListener;
import com.gnaw.discovery.event.ClientFoundEvent;
import com.gnaw.discovery.event.ClientFoundEventListener;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BeaconServerThread extends Thread {

    protected DatagramSocket socket = null;
    protected boolean runFlag = false;
    protected int timeToLive = -1;
    protected ArrayList<BroadcastingEndEventListener> listeners;

    public BeaconServerThread() throws IOException {
        this(300);
    }

    public BeaconServerThread(int seconds) throws IOException {
        super("BeaconServerThread");
        this.socket = new DatagramSocket(4445);
        this.timeToLive = seconds;
    }

    public void setListener(ArrayList<BroadcastingEndEventListener> listeners) {
        this.listeners = listeners;
    }

    @Override
    public void run() {
        int counter = timeToLive;
        byte[] buf;
        this.runFlag = true;
        while (this.runFlag && counter != 0) {
            try {

                // figure out response
                String dString = Integer.toString(counter);

                buf = dString.getBytes();

                InetAddress group = InetAddress.getByName("224.0.113.0");
                DatagramPacket packet;
                packet = new DatagramPacket(buf, buf.length, group, 4446);
                this.socket.send(packet);
                if(counter != -1){
                    counter--;                	
                }
                System.out.println("Pinged to address " + group.getHostAddress());
                Thread.sleep(1000);
            } catch (IOException e) {
                Logger.getLogger(BeaconServerThread.class.getName()).log(Level.SEVERE, null, e);
                runFlag = false;
            } catch (InterruptedException ex) {
                Logger.getLogger(BeaconServerThread.class.getName()).log(Level.SEVERE, null, ex);
                runFlag = false;
            }
        }
        this.socket.close();
        fireBroadcastingEndEvent(new BroadcastingEndEvent("Event"));
    }

    public void stopSignal() {
        this.runFlag = false;
    }
    
    
    private void fireBroadcastingEndEvent(BroadcastingEndEvent evt) {
        for (BroadcastingEndEventListener listener : listeners) {
            listener.BroadcastingEndEventOccurred(evt);
        }
    }
}
