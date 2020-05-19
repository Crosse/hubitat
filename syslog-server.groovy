/*
    Syslog Server driver for Hubitat Elevation

    Copyright (c) 2020 Seth Wright <seth@crosse.org>

    Permission to use, copy, modify, and/or distribute this software for any purpose with or
    without fee is hereby granted, provided that the above copyright notice and this permission
    notice appear in all copies.

    THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS
    SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL
    THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY
    DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF
    CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE
    OR PERFORMANCE OF THIS SOFTWARE.
 */

import groovy.json.JsonSlurper
import java.time.LocalDateTime
import java.text.SimpleDateFormat
import hubitat.device.HubAction
import hubitat.device.Protocol

metadata {
    definition (
        name: "Syslog Server",
        namespace: "org.crosse",
        author: "Seth Wright",
        category: "Misc",
        description: "Syslog Server",
        iconUrl: "",
        iconX2Url: "",
        iconX3Url: "",
        importUrl: "https://github.com/Crosse/hubitat/syslog-server.groovy",
        singleInstance: false,
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "HealthCheck"
    }
}

preferences {
    input (
        name: "server",
        type: "string",
        title: "Server IP Address",
        description: "The IP address of the syslog server.",
        required: true
    )
    input (
        name: "port",
        type: "integer",
        title: "Server Port",
        description: "The port to which to send syslog data. <em>Default: 514</em>.",
        defaultValue: 514,
        required: true
    )
    input (
        name: "facility",
        type: "enum",
        title: "Syslog Facility",
        description: "The syslog facility to assign to all events from this hub. <em>Default: local0</em>.",
        defaultValue: "local0",
        required: true,
        options: syslogFacilities()
    )
    input (
        name: "syslogStyle",
        type: "enum",
        title: "Syslog Style",
        description: "How syslog messages should be formatted",
        defaultValue: "bsd",
        required: true,
        options: ["bsd", "ietf"]
    )
    input (
        name: "debugLogging",
        type: "bool",
        title: "Enable debug logging",
        defaultValue: true
    )
}

private def syslogFacilities() {
    return [
        "kern",
        "user",
        "mail",
        "daemon",
        "auth",
        "syslog",
        "lpr",
        "news",
        "uucp",
        "cron",
        "authpriv",
        "ftp",
        "ntp",
        "security",
        "console",
        "clock daemon",
        "local0",
        "local1",
        "local2",
        "local3",
        "local4",
        "local5",
        "local6",
        "local7"
    ]
}

private facilityId(String facility) {
    return syslogFacilities().indexOf(facility)
}

// TODO: convert hubitat levels to syslog levels
private def syslogPriority(String lvl) {
    // These are known values, as defined by multiple RFCs. We can simply return them.
    switch (lvl) {
        case "error":
            return 3
        case "warn":
            return 4
        case "info":
            return 6
        case "debug":
            return 7
        case "trace":
            return 7
        default:
            // If we ask for something that doesn't exist, then just make it informational.
            return 6
    }
}

private logDebug(String message) {
    if (debugLogging) {
        log.debug message
    }
}

def installed() {
    logDebug "install() called"
    updated()
}

def updated() {
    logDebug "updated() called"
    disconnect()
    initialize()
}

def initialize() {
    logDebug "initialize() called"

    connect()
    fId = facilityId(facility)
    logDebug "sending ${syslogStyle}-formatted events to ${device.name}:${port} using facility ${facility} ($fId)"
}

def refresh() {
    disconnect()
    connect()
}

def connect() {
    if (state.connected) {
        log.warn "already connected to the web socket"
        return
    }

    logDebug "connecting to web socket"
    def hub = location.hub
    interfaces.webSocket.connect("http://127.0.0.1:8080/logsocket")
    state.connected = true
}

def disconnect() {
    if (!state.connected) {
        log.warn "not connected to the web socket"
        return
    }

    logDebug "disconnecting from web socket"
    interfaces.webSocket.close()
    state.connected = false
}

def parse(String message) {
    if (!state.connected) {
        return
    }

    def jsonSlurper = new JsonSlurper()
    def evt = jsonSlurper.parseText(message)

    if (evt.type == "dev" && evt.id == device.getIdAsLong()) {
        // this is one of ours; do not log it to avoid a log storm
        return
    }

    priority = syslogPriority(evt.level)
    ts = getDateTime(evt.time)
    facId = facilityId(facility)

    String msg
    switch (syslogStyle) {
        case "bsd":
            msg = bsdFormatter(facId, priority, ts, evt.name, evt.msg)
        case "ietf":
            msg = ietfFormatter(facId, priority, ts, evt.name, evt.msg)
    }

    opts = [
        type: HubAction.Type.LAN_TYPE_UDPCLIENT,
        destinationAddress: "${server}:${port}",
        ignoreResponse: true
    ]
    new HubAction(msg, Protocol.LAN, opts)
}

def webSocketStatus(String message) {
    status = message.split()
    /*
    switch (status[1]) {
        case "open":
            state.connected = true
        case "closing":
            state.connected = false
    }
    */
    logDebug "web socket status: ${status[1]}"
}

private Date getDateTime(String timestamp) {
    fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    return fmt.parse(timestamp)
}

private def bsdFormatter(int facility, int priority, Date dt, String name, String message) {
    fmt = new SimpleDateFormat("LLL dd HH:mm:ss")
    timestamp = fmt.format(dt)

    pri = (facility * 8) + priority
    hubIp = location.hub.localIP

    return "<$pri>$timestamp $hubIp hubitat: $name: $message"
}

private def ietfFormatter(int facility, int priority, Date dt, String name, String message) {
    pri = (facility * 8) + priority
    timestamp = ts.format("LLL dd HH:mm:ss")
    hubIp = location.hub.localIP

    // XXX: THIS IS NOT RIGHT
    return "<$pri>$timestamp $hubIp hubitat: $name: $message"
}
