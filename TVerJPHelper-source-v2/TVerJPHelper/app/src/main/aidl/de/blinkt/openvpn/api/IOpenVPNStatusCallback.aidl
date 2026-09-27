package de.blinkt.openvpn.api;
oneway interface IOpenVPNStatusCallback {
    void newStatus(String uuid, String state, String message, String level);
}
