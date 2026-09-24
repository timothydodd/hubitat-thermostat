/**
 * Honeywell T6 Pro Z-Wave Thermostat (Lite)
 *
 * Author: Tim Dodd
 * Version: 1.0.1
 *
 * Based on "Advanced Honeywell T6 Pro" by Bryan Copeland (djdizzyd)
 * https://github.com/djdizzyd/hubitat
 *
 * A lightweight driver focused on low hub load:
 *  - One Z-Wave round trip per command, none triggered by incoming reports
 *  - No per-report database reads or state writes
 *  - Thermostat installer settings are left to the thermostat itself
 *
 * Changelog
 *  1.0.1 (2026-09-24) - Home/Away, TH6320ZW2007 fingerprints, calibration wording for Celsius
 *  1.0.0 (2026-09-24) - Initial release
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.math.RoundingMode

metadata {
    definition(name: "Honeywell T6 Pro Thermostat (Lite)", namespace: "dodd", author: "Tim Dodd") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Configuration"
        capability "Battery"
        capability "PowerSource"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Thermostat"
        capability "ThermostatMode"
        capability "ThermostatFanMode"
        capability "ThermostatSetpoint"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatOperatingState"

        attribute "sensorCalibration", "number"
        attribute "idleBrightness", "number"
        attribute "occupancy", "enum", ["home", "away"]

        command "setSensorCalibration", [[name: "offset*", type: "ENUM", description: "Sensor offset in steps: 1°F each, or 0.5°C each when the thermostat is set to Celsius", constraints: ["-3", "-2", "-1", "0", "1", "2", "3"]]]
        command "setIdleBrightness", [[name: "level*", type: "ENUM", description: "Display brightness when idle", constraints: ["0", "1", "2", "3", "4", "5"]]]
        command "syncClock"
        command "home"
        command "away"

        fingerprint mfr: "0039", prod: "0011", deviceId: "0008", inClusters: "0x5E,0x85,0x86,0x59,0x31,0x80,0x81,0x70,0x5A,0x72,0x71,0x73,0x9F,0x44,0x45,0x40,0x42,0x43,0x6C,0x55", deviceJoinName: "Honeywell T6 Pro"
        // TH6320ZW2007 (T6 Pro with SmartStart)
        fingerprint mfr: "041B", prod: "0011", deviceId: "0009", deviceJoinName: "Honeywell T6 Pro"
        fingerprint mfr: "041B", prod: "0011", deviceId: "000A", deviceJoinName: "Honeywell T6 Pro"
    }

    preferences {
        input "txtEnable", "bool", title: "Enable descriptionText logging", defaultValue: true
        input "logEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
    }
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

@Field static final Map<Integer, Integer> CMD_CLASS_VERS = [
    0x20: 1,  // Basic
    0x31: 5,  // Sensor Multilevel
    0x40: 2,  // Thermostat Mode
    0x42: 1,  // Thermostat Operating State
    0x43: 2,  // Thermostat Setpoint
    0x44: 3,  // Thermostat Fan Mode
    0x6C: 1,  // Supervision
    0x70: 1,  // Configuration
    0x71: 3,  // Notification
    0x80: 1,  // Battery
    0x81: 1,  // Clock
    0x85: 2,  // Association
]

@Field static final Integer SETPOINT_HEAT = 1
@Field static final Integer SETPOINT_COOL = 2

@Field static final Integer PARAM_IDLE_BRIGHTNESS = 39
@Field static final Integer PARAM_SENSOR_CAL = 42

@Field static final Map<Integer, String> MODE_NAMES = [0: "off", 1: "heat", 2: "cool", 3: "auto", 4: "emergency heat"]
@Field static final Map<Integer, String> FAN_MODE_NAMES = [0: "auto", 1: "on", 2: "auto", 3: "on", 4: "auto", 5: "on", 6: "circulate", 7: "circulate"]
@Field static final Map<String, Integer> FAN_MODE_VALUES = ["auto": 0, "on": 1, "circulate": 6]
@Field static final Map<Integer, String> OPERATING_STATE_NAMES = [0: "idle", 1: "heating", 2: "cooling", 3: "fan only", 4: "pending heat", 5: "pending cool", 6: "vent economizer"]

@Field static final List<String> HEAT_STATES = ["heating", "pending heat"]
@Field static final List<String> COOL_STATES = ["cooling", "pending cool"]

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void installed() {
    initialize()
    runIn(5, "refresh")
}

void updated() {
    log.info "${device.displayName} preferences saved"
    unschedule()
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void configure() {
    logDebug "configure()"
    // Lifeline so the thermostat reports changes to the hub on its own
    send([
        zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: [zwaveHubNodeId]),
        zwave.associationV2.associationGet(groupingIdentifier: 1)
    ])
    initialize()
    runIn(5, "refresh")
    runIn(10, "syncClock")
}

void initialize() {
    sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(MODE_NAMES.values() as List))
    sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(FAN_MODE_VALUES.keySet() as List))
    // Once a day, after any DST change has happened
    schedule("0 15 3 * * ?", "syncClock")
}

void logsOff() {
    log.warn "${device.displayName} debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

void refresh() {
    logDebug "refresh()"
    send([
        zwave.thermostatModeV2.thermostatModeGet(),
        zwave.thermostatOperatingStateV1.thermostatOperatingStateGet(),
        zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: SETPOINT_HEAT),
        zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: SETPOINT_COOL),
        zwave.thermostatFanModeV3.thermostatFanModeGet(),
        zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 1, scale: hubIsFahrenheit() ? 1 : 0),
        zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 5, scale: 0),
        zwave.batteryV1.batteryGet(),
        zwave.basicV1.basicGet(),
        zwave.configurationV1.configurationGet(parameterNumber: PARAM_IDLE_BRIGHTNESS),
        zwave.configurationV1.configurationGet(parameterNumber: PARAM_SENSOR_CAL)
    ])
}

void syncClock() {
    Calendar now = Calendar.getInstance(location.timeZone)
    // Z-Wave weekday is 1=Monday..7=Sunday; Java's is 1=Sunday..7=Saturday
    int weekday = ((now.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    logDebug "syncClock() ${now.time}"
    send(zwave.clockV1.clockSet(hour: now.get(Calendar.HOUR_OF_DAY), minute: now.get(Calendar.MINUTE), weekday: weekday))
}

void setSensorCalibration(offset) {
    logDebug "setSensorCalibration(${offset})"
    setConfig(PARAM_SENSOR_CAL, offset as Integer)
}

void setIdleBrightness(level) {
    logDebug "setIdleBrightness(${level})"
    setConfig(PARAM_IDLE_BRIGHTNESS, level as Integer)
}

void setHeatingSetpoint(temperature) {
    logDebug "setHeatingSetpoint(${temperature})"
    setSetpoint(SETPOINT_HEAT, temperature)
}

void setCoolingSetpoint(temperature) {
    logDebug "setCoolingSetpoint(${temperature})"
    setSetpoint(SETPOINT_COOL, temperature)
}

void setThermostatMode(String mode) {
    logDebug "setThermostatMode(${mode})"
    Integer value = MODE_NAMES.find { it.value == mode }?.key
    if (value == null) {
        log.warn "${device.displayName} unsupported thermostat mode: ${mode}"
        return
    }
    send([
        zwave.thermostatModeV2.thermostatModeSet(mode: value),
        zwave.thermostatModeV2.thermostatModeGet()
    ], 1000)
}

void off()           { setThermostatMode("off") }
void heat()          { setThermostatMode("heat") }
void cool()          { setThermostatMode("cool") }
void auto()          { setThermostatMode("auto") }
void emergencyHeat() { setThermostatMode("emergency heat") }

void setThermostatFanMode(String mode) {
    logDebug "setThermostatFanMode(${mode})"
    Integer value = FAN_MODE_VALUES[mode]
    if (value == null) {
        log.warn "${device.displayName} unsupported fan mode: ${mode}"
        return
    }
    send([
        zwave.thermostatFanModeV3.thermostatFanModeSet(fanMode: value),
        zwave.thermostatFanModeV3.thermostatFanModeGet()
    ], 1000)
}

void fanOn()        { setThermostatFanMode("on") }
void fanAuto()      { setThermostatFanMode("auto") }
void fanCirculate() { setThermostatFanMode("circulate") }

void setSchedule(json) {
    log.warn "${device.displayName} setSchedule is not supported; use the thermostat's own schedule"
}

// Basic Set to the thermostat switches it between Home (0xFF) and Away (0x00)
void home() {
    logDebug "home()"
    send([zwave.basicV1.basicSet(value: 0xFF), zwave.basicV1.basicGet()], 1000)
}

void away() {
    logDebug "away()"
    send([zwave.basicV1.basicSet(value: 0x00), zwave.basicV1.basicGet()], 1000)
}

void pollOperatingState() {
    send(zwave.thermostatOperatingStateV1.thermostatOperatingStateGet())
}

private void setSetpoint(Integer setpointType, temperature) {
    boolean fahrenheit = hubIsFahrenheit()
    BigDecimal value = temperature as BigDecimal
    // The T6 takes whole degrees in F and half degrees in C
    value = fahrenheit
        ? value.setScale(0, RoundingMode.HALF_UP)
        : (value * 2).setScale(0, RoundingMode.HALF_UP) / 2
    int precision = value.stripTrailingZeros().scale() > 0 ? 1 : 0
    send([
        zwave.thermostatSetpointV2.thermostatSetpointSet(setpointType: setpointType, scale: fahrenheit ? 1 : 0, precision: precision, size: 2, scaledValue: value),
        zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: setpointType)
    ], 1000)
}

private void setConfig(Integer parameter, Integer value) {
    send([
        zwave.configurationV1.configurationSet(parameterNumber: parameter, size: 1, scaledConfigurationValue: value),
        zwave.configurationV1.configurationGet(parameterNumber: parameter)
    ])
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

void parse(String description) {
    hubitat.zwave.Command cmd = zwave.parse(description, CMD_CLASS_VERS)
    if (cmd) {
        logDebug "parsed: ${cmd}"
        zwaveEvent(cmd)
    } else {
        logDebug "unparsed: ${description}"
    }
}

void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    hubitat.zwave.Command inner = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (inner) zwaveEvent(inner)
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd) {
    hubitat.zwave.Command inner = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (inner) zwaveEvent(inner)
    send(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0))
}

void zwaveEvent(hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport cmd) {
    String mode = MODE_NAMES[cmd.mode as Integer]
    if (!mode) return
    emit("thermostatMode", mode)
    updateThermostatSetpoint(mode, device.currentValue("thermostatOperatingState"))
}

void zwaveEvent(hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport cmd) {
    String opState = OPERATING_STATE_NAMES[cmd.operatingState as Integer]
    if (!opState) return
    emit("thermostatOperatingState", opState)

    // Remember which way auto mode last went, so the idle setpoint stays sensible
    String direction = opState in HEAT_STATES ? "heat" : opState in COOL_STATES ? "cool" : null
    if (direction && state.lastDirection != direction) state.lastDirection = direction

    String mode = device.currentValue("thermostatMode")
    if (mode == "auto") updateThermostatSetpoint(mode, opState)
}

void zwaveEvent(hubitat.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport cmd) {
    String direction = cmd.setpointType == SETPOINT_HEAT ? "heat" : cmd.setpointType == SETPOINT_COOL ? "cool" : null
    if (!direction) return

    BigDecimal value = toHubTemperature(cmd.scaledValue, cmd.scale, hubIsFahrenheit() ? 0 : 1)
    emit(direction == "heat" ? "heatingSetpoint" : "coolingSetpoint", value, temperatureUnit())

    if (activeDirection(device.currentValue("thermostatMode"), device.currentValue("thermostatOperatingState")) == direction) {
        emit("thermostatSetpoint", value, temperatureUnit())
    }
}

void zwaveEvent(hubitat.zwave.commands.thermostatfanmodev3.ThermostatFanModeReport cmd) {
    String mode = FAN_MODE_NAMES[cmd.fanMode as Integer]
    if (mode) emit("thermostatFanMode", mode)
}

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
    switch (cmd.sensorType as Integer) {
        case 1:
            emit("temperature", toHubTemperature(cmd.scaledSensorValue, cmd.scale, 1), temperatureUnit())
            break
        case 5:
            emit("humidity", Math.round(cmd.scaledSensorValue), "%")
            break
    }
}

void zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    // 0xFF is the low-battery warning
    emit("battery", cmd.batteryLevel == 0xFF ? 1 : cmd.batteryLevel, "%")
}

void zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
    if (cmd.notificationType != 8) return  // Power Management only
    switch (cmd.event as Integer) {
        case 1:  // power applied
        case 3:  // AC re-connected
            emit("powerSource", "mains")
            break
        case 2:  // AC disconnected
            emit("powerSource", "battery")
            break
    }
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    switch (cmd.parameterNumber as Integer) {
        case PARAM_IDLE_BRIGHTNESS:
            emit("idleBrightness", cmd.scaledConfigurationValue)
            break
        case PARAM_SENSOR_CAL:
            emit("sensorCalibration", cmd.scaledConfigurationValue)
            break
    }
}

void zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
    // The T6 sends this when equipment starts/stops. Only ask for the real state
    // when it disagrees with ours, and debounce so a burst costs one request.
    boolean running = device.currentValue("thermostatOperatingState") in ["heating", "cooling"]
    if ((cmd.value == 0xFF) != running) runIn(3, "pollOperatingState")
}

// Answer to our Basic Get: 0 is Away, anything else is Home
void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    emit("occupancy", cmd.value == 0 ? "away" : "home")
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
    updateDataValue("zwaveAssociationG${cmd.groupingIdentifier}", cmd.nodeId.collect { String.format("%02X", it) }.toString())
}

void zwaveEvent(hubitat.zwave.Command cmd) {
    logDebug "ignored: ${cmd}"
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

// Which setpoint is "the" setpoint right now: follow the mode, or in auto
// follow what the equipment is doing (or last did).
private String activeDirection(String mode, String opState) {
    if (mode in ["heat", "emergency heat"]) return "heat"
    if (mode == "cool") return "cool"
    if (opState in HEAT_STATES) return "heat"
    if (opState in COOL_STATES) return "cool"
    return state.lastDirection ?: "heat"
}

private void updateThermostatSetpoint(String mode, String opState) {
    String source = activeDirection(mode, opState) == "heat" ? "heatingSetpoint" : "coolingSetpoint"
    def value = device.currentValue(source)
    if (value != null) emit("thermostatSetpoint", value, temperatureUnit())
}

private boolean hubIsFahrenheit() {
    return getTemperatureScale() == "F"
}

private String temperatureUnit() {
    return "°${getTemperatureScale()}"
}

private BigDecimal toHubTemperature(BigDecimal value, Integer deviceScale, int precision) {
    return new BigDecimal(convertTemperatureIfNeeded(value, deviceScale == 1 ? "F" : "C", precision))
}

// sendEvent already drops unchanged values, so only read the current value
// when descriptionText logging needs to know whether this is a change.
private void emit(String name, value, String unit = null) {
    Map evt = [name: name, value: value]
    if (unit) evt.unit = unit
    if (txtEnable && device.currentValue(name)?.toString() != value?.toString()) {
        evt.descriptionText = "${device.displayName} ${name} is ${value}${unit ?: ''}"
        log.info evt.descriptionText
    }
    sendEvent(evt)
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

private void send(List<hubitat.zwave.Command> cmds, Long delay = 300) {
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds.collect { zwaveSecureEncap(it.format()) }, delay), hubitat.device.Protocol.ZWAVE))
}

private void send(hubitat.zwave.Command cmd) {
    sendHubCommand(new hubitat.device.HubAction(zwaveSecureEncap(cmd.format()), hubitat.device.Protocol.ZWAVE))
}
