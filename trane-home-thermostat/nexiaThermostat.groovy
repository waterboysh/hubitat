/**
 *	Nexia Thermostat
 *
 *	Author: Trent Foley
 *	Date: 2016-01-25
 *
 *
 * ** Modifications **
 *  Date        Who          Description
 *  __________  __________   ____________________________________________________________________
 *  2022-09-14  thebearmay   port over to Hubitat
 *  2022-09-16	thebearmay   Fix thermostatOperatingState
 *  2022-10-04  thebearmay   Add permanent hold and return to schedule
 *  2024-01-21  thebearmay   Add supportedThermostatModes
 *  2026-06-04  Codex        Make emergency heat support configurable
 *  2026-06-04  Codex        Add compressor speed attribute
 *  2026-06-05  Codex        Add selectable logging levels
 */
static String version()	{  return '1.1'  }

metadata {
    definition (name: "Nexia Thermostat", 
                namespace: "trentfoley", 
                author: "Trent Foley",
                importUrl:"https://raw.githubusercontent.com/waterboysh/hubitat/main/trane-home-thermostat/nexiaThermostat.groovy",                
               ) {
        capability "Actuator"
        capability "Temperature Measurement"
        capability "Thermostat"
        capability "Relative Humidity Measurement"
        capability "Polling"
        capability "Sensor"
        capability "Refresh"
        capability "Initialize"	    
        command "setTemperature", [[name:"Target Temperature*", type:"NUMBER", description:"Adjusts the active heating or cooling setpoint toward this target temperature"]]
        command "setHold", [[name:"holdType", type:"ENUM", constraints:["Permanent Hold","Return to Schedule"]]]

        attribute "activeMode", "string"
        attribute "outdoorTemperature", "number"
        attribute "thermostatOperatingState", "string"
        attribute "holdStatus", "string"
        attribute "compressorSpeed", "number"
	attribute "supportedThermostatModes", "string"    
    }
}

preferences {
    input "pollInterval", "enum", title:"Enter Poll Cycle", options:['1 Minute','5 Minutes','10 Minutes','15 Minutes','30 Minutes','1 Hour','3 Hours'], defaultValue:'1 Minute', submitOnChange:true
    input "emergencyHeatSupported", "bool", title:"Emergency Heat Mode Supported", defaultValue:false, submitOnChange:true
    input "loggingLevel", "enum", title:"Logging Level", options:["Off", "Info", "Debug for 30 minutes", "Debug for 4 hours", "Debug for 24 hours", "Debug always"], defaultValue:"Off", submitOnChange:true

}

def updated() {
    def selectedPollInterval = pollInterval ?: "1 Minute"
    logDebug("update ${selectedPollInterval}")
    unschedule()
    configureLogging()
    updateSupportedThermostatModes()
    logInfo("Polling interval set to ${selectedPollInterval}")
    switch(selectedPollInterval){
        case "1 Minute":
			runEvery1Minute("poll")
            break
        case "5 Minutes":
			runEvery5Minutes("poll")
            break
        case "10 Minutes":
			runEvery10Minutes("poll")
            break
        case "15 Minutes":
			runEvery15Minutes("poll")
            break
        case "30 Minutes":
			runEvery30Minutes("poll")
            break
        case "1 Hour":
			runEvery1Hour("poll")
            break
        case "3 Hours":
			runEvery3Hours("poll")
            break	
		default:
			log.error "Invalid Interval Selected ${selectedPollInterval}"
			break
    }
            
}
def initialize(){
    updateSupportedThermostatModes()
    poll()
}

private void updateSupportedThermostatModes() {
    def modes = emergencyHeatSupported ? '["auto", "off", "heat", "emergency heat", "cool"]' : '["auto", "off", "heat", "cool"]'
    sendEvent(name:'supportedThermostatModes', value:modes)
}

// Logging levels are preference-driven so normal installs can stay quiet while
// temporary debug sessions turn themselves off automatically.
private String getConfiguredLoggingLevel() {
    return settings.loggingLevel ?: "Off"
}

private Boolean isInfoLoggingEnabled() {
    return configuredLoggingLevel != "Off"
}

private Boolean isDebugLoggingEnabled() {
    return configuredLoggingLevel?.startsWith("Debug")
}

private void logInfo(message) {
    if(isInfoLoggingEnabled()) log.info message
}

private void logDebug(message) {
    if(isDebugLoggingEnabled()) log.debug message
}

private void configureLogging() {
    switch(configuredLoggingLevel) {
        case "Debug for 30 minutes":
            runIn(1800, "logsOff")
            break
        case "Debug for 4 hours":
            runIn(14400, "logsOff")
            break
        case "Debug for 24 hours":
            runIn(86400, "logsOff")
            break
    }
}

// This cloud driver does not receive LAN parse messages, but Hubitat expects
// parse() to exist for many driver types.
def parse(String description) {
    logDebug("parse('${description}')")
}

// Implementation of capability.refresh
def refresh() {
    logDebug("refresh()")
    poll()
}

// Implementation of capability.polling
def poll() {
    logDebug("poll()")
    def data = parent.pollChild(this)
    logDebug("${data}")

    if(data) {
        def oldTemperature = device.currentValue("temperature")
        def oldHumidity = device.currentValue("humidity")
        def oldOperatingState = device.currentValue("thermostatOperatingState")
        // The parent app normalizes Trane Home JSON into this map. The driver
        // owns publishing those values as Hubitat Current States.
        sendEvent(name: "temperature", value: data.temperature, unit: "°F")
            sendEvent(name: "heatingSetpoint", value: data.heatingSetpoint, unit: "°F")
            sendEvent(name: "coolingSetpoint", value: data.coolingSetpoint, unit: "°F")
            sendEvent(name: "thermostatSetpoint", value: data.thermostatSetpoint, unit: "°F")
            state.minHeatingSetpoint = data.minHeatingSetpoint ?: 55
            state.maxHeatingSetpoint = data.maxHeatingSetpoint ?: 90
            state.minCoolingSetpoint = data.minCoolingSetpoint ?: 60
            state.maxCoolingSetpoint = data.maxCoolingSetpoint ?: 99
            sendEvent(name: "thermostatMode", value: data.thermostatMode)
            sendEvent(name: "thermostatFanMode", value: data.thermostatFanMode)
            sendEvent(name: "thermostatOperatingState", value: data.thermostatOperatingState)
            sendEvent(name: "humidity", value: data.humidity, unit: "%")
            sendEvent(name: "activeMode", value: data.activeMode)
            sendEvent(name: "outdoorTemperature", value: data.outdoorTemperature, unit: "°F")
            sendEvent(name: "holdStatus", value: data.setpointStatus)
            if(data.compressorSpeed != null) {
                sendEvent(name: "compressorSpeed", value: data.compressorSpeed)
            }
            if(oldTemperature != null && oldTemperature?.toString() != data.temperature?.toString()) logInfo("Temperature changed from ${oldTemperature} to ${data.temperature}")
            if(oldHumidity != null && oldHumidity?.toString() != data.humidity?.toString()) logInfo("Humidity changed from ${oldHumidity} to ${data.humidity}")
            if(oldOperatingState != null && oldOperatingState?.toString() != data.thermostatOperatingState?.toString()) logInfo("Operating state changed from ${oldOperatingState} to ${data.thermostatOperatingState}")
    } else {
        log.error "ERROR: Device connection removed? No data found for ${device.deviceNetworkId} after polling"
    }
}

def setHold(hType){
    hType = hType.toLowerCase().replace(" ","_")
    logInfo("Sending hold command: ${hType}")
    parent.setHoldMode(this, hType)
    if(hType == "permanent_hold") {
        sendEvent(name:"holdStatus",value:"Permanent Hold Requested")
    } else {
        sendEvent(name:"holdStatus",value:"Return to Schedule Requested")
    }
}

// Backward-compatible custom command from the original driver. It does not set
// measured room temperature; it shifts the active setpoint by the requested
// difference from current temperature.
def setTemperature(degreesF) {
    logInfo("Sending temperature compatibility command: ${degreesF}")
    logDebug("setTemperature(${degreesF})")
    def currentTemperature = device.currentValue("temperature")
    if(currentTemperature == null) {
        log.warn "Cannot run setTemperature because current temperature is unknown."
        return
    }

    def delta = degreesF - currentTemperature
    logDebug("Determined delta to be ${delta}")

    def thermostatMode = device.currentValue("thermostatMode")
    def activeMode = device.currentValue("activeMode")

    if(thermostatMode == "off") {
        log.warn "setTemperature ignored because thermostat mode is off."
    } else if(thermostatMode == "cool") {
        setCoolingSetpoint(device.currentValue("coolingSetpoint") + delta)
    } else if(thermostatMode == "heat") {
        setHeatingSetpoint(device.currentValue("heatingSetpoint") + delta)
    } else if(activeMode == "cool") {
        setCoolingSetpoint(device.currentValue("coolingSetpoint") + delta)
    } else {
        setHeatingSetpoint(device.currentValue("heatingSetpoint") + delta)
    }
}

// Trane Home reports per-thermostat setpoint limits. Cache the latest values
// from poll(), and fall back to conservative Fahrenheit defaults if absent.
private BigDecimal clampSetpoint(value, minValue, maxValue, setpointName) {
    BigDecimal requestedValue = value.toBigDecimal()
    BigDecimal minimumValue = minValue.toBigDecimal()
    BigDecimal maximumValue = maxValue.toBigDecimal()
    BigDecimal clampedValue = requestedValue

    if(requestedValue < minimumValue) {
        clampedValue = minimumValue
    } else if(requestedValue > maximumValue) {
        clampedValue = maximumValue
    }

    if(clampedValue != requestedValue) {
        logInfo("${setpointName} setpoint ${requestedValue} is outside allowed range ${minimumValue}-${maximumValue}; using ${clampedValue}")
    }

    return clampedValue
}

private BigDecimal clampHeatingSetpoint(value) {
    return clampSetpoint(value, state.minHeatingSetpoint ?: 55, state.maxHeatingSetpoint ?: 90, "Heating")
}

private BigDecimal clampCoolingSetpoint(value) {
    return clampSetpoint(value, state.minCoolingSetpoint ?: 60, state.maxCoolingSetpoint ?: 99, "Cooling")
}

// Standard thermostat capability commands. Hubitat apps/dashboards may call
// either setThermostatMode(...) or these convenience methods.
def setHeatingSetpoint(degreesF) {
    def heatingSetpoint = clampHeatingSetpoint(degreesF)
    logInfo("Sending heating setpoint command: ${heatingSetpoint}")
    logDebug("setHeatingSetpoint(${degreesF})")
    sendEvent(name: "heatingSetpoint", value: heatingSetpoint, unit: "°F")
    sendEvent(name: "thermostatSetpoint", value: heatingSetpoint, unit: "°F")
    parent.setHeatingSetpoint(this, heatingSetpoint)
}

def setCoolingSetpoint(degreesF) {
    def coolingSetpoint = clampCoolingSetpoint(degreesF)
    logInfo("Sending cooling setpoint command: ${coolingSetpoint}")
    logDebug("setCoolingSetpoint(${degreesF})")
    sendEvent(name: "coolingSetpoint", value: coolingSetpoint, unit: "°F")
    sendEvent(name: "thermostatSetpoint", value: coolingSetpoint, unit: "°F")
    parent.setCoolingSetpoint(this, coolingSetpoint)
}

// Valid values are: "auto" "heat" "off" "cool"; "emergency heat" is opt-in.
def setThermostatMode(String mode) {
    if(mode == "emergency heat" && !emergencyHeatSupported) {
        log.warn "Emergency heat mode is disabled in driver preferences and will not be sent."
        return
    }
    logInfo("Sending thermostat mode command: ${mode}")
    logDebug("setThermostatMode(${mode})")
    sendEvent(name: "thermostatMode", value: mode)
    parent.setThermostatMode(this, mode)
}

def off() { setThermostatMode("off") }

def heat() { setThermostatMode("heat") }

def emergencyHeat() {
    if(emergencyHeatSupported) {
        setThermostatMode("emergency heat")
    } else {
        log.warn "Emergency heat mode is disabled in driver preferences and will not be sent."
    }
}

def cool() { setThermostatMode("cool") }

def auto() { setThermostatMode("auto") }

// Valid values are: "auto" "on" "circulate"
def setThermostatFanMode(String fanMode) {
    logInfo("Sending thermostat fan mode command: ${fanMode}")
    logDebug("setThermostatFanMode(${fanMode})")
    sendEvent(name: "thermostatFanMode", value: fanMode)
    parent.setThermostatFanMode(this, fanMode)
}

def fanOn() { setThermostatFanMode("on") }

def fanAuto() { setThermostatFanMode("auto") }

def fanCirculate() { setThermostatFanMode("circulate") }

void logsOff(){
    device.updateSetting("loggingLevel",[value:"Off",type:"enum"])
}


