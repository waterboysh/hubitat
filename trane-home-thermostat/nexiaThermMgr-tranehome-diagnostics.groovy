/**
 *  Copyright 2015 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *    Nexia Thermostat Service Manager
 *
 *    Author: Trent Foley
 *    Date: 2016-01-19
 *
 * **	Modifications **
 *	Date		Who		    Description
 *	2022-09-15	thebearmay	Port to Hubitat
 *	2022-09-16	thebearmay	Fix thermostatOperatingMode
 *  2022-10-04  thebearmay  Add permanent hold and return to schedule
 *  2022-10-07  thebearmay  Option to use American Standard Login
 *  2026-06-04  Codex        Add Trane Home diagnostics support for newer thermostats
 *  2026-06-05  Codex        Categorize app under Integrations
 *
 */
static String version()	{  return '1.1' }

definition(
    name: "Nexia Thermostat Manager",
    namespace: "trentfoley",
    author: "Trent Foley",
    description: "Connect your Nexia thermostat to Hubitat.",
    category: "Convenience",
    menu: "Integrations",
	importUrl:"https://raw.githubusercontent.com/thebearmay/hubitat/main/STPorts/nexiaThermMgr.groovy",    
    iconUrl: "http://lh4.ggpht.com/oMx3-nlICwLmUxpDhTXWsZ6Ocuzu9P2yfz9jpXBx1rhrW_Vcj94kPl2M9ooApckK6TM1=w60",
    iconX2Url: "https://www.trane.com/content/dam/Trane/residential/products/nexia/medium/TR_Nexia%20-%20Medium.jpg",
    iconX3Url: "https://www.trane.com/content/dam/Trane/residential/products/nexia/medium/TR_Nexia%20-%20Medium.jpg",
    singleInstance: true
) { }

preferences {
    section("<h2 style='color:blue'>Nexia Authentication<br><span style='font-size:small'>v${version()}</span></h2>") {
        input "username", "text", title: "Username"
        input "password", "password", title: "Password"
        input "debugEnabled", "bool", title: "Enable debug logging?", width:4
        input "useAmerStand", "bool", title: "Use American Standard login", width:4, defaultValue:false
        if(debugEnabled) runIn(1800, "logsOff")
    }
}

def getChildNamespace() { "trentfoley" }
def getChildName() { "Nexia Thermostat" }
def getServerUrl() { 
    if(useAmerStand) 
        return "https://asairhome.com/login" 
    else
        return "https://www.tranehome.com/login" 
}

def installed() {
    if(debugEnabled) log.debug("installed()")
    initialize()
}

def updated() {
    if(debugEnabled) log.debug("updated()")
    unsubscribe()
    initialize()
}

def initialize() {
    if(debugEnabled) log.debug("initialize()")
    
    // Ensure authenticated
    refreshAuthToken()

    // Newer Trane Home thermostats no longer appear in xxl_thermostats.
    // They are discovered from diagnostics URLs embedded in the climate page.
    state.diagnosticThermostats = [:]
    
    // Get list of thermostats and ensure child devices
    def homeParams = [
        //method: 'GET',
        uri: serverUrl,
        headers: getDefaultHeaders()
    ]

    try {
        httpGet(homeParams) { homeResp ->

        	def respData = homeResp.data[0]
        	
            // html / body / div id=footer-wrapper / div id=content / div id=content_sidebar / nav / ul / li / a id=climate_link
            // Recursive search for climate/index link.  Should be more robust to Nexia DOM changes
            respData.children().each{
                searchForClimate(it)
            }
        }
    }
    catch(e) {
        log.error("Caught exception determining thermostats path ${e}")
    }

    discoverDiagnosticThermostats()
    
    // Get list of thermostats and ensure child devices
    requestThermostats { thermostatsResp ->
        def devices = []
        if(thermostatsResp.data && thermostatsResp.data.size() > 0) {
            devices = thermostatsResp.data.collect { stat ->
                if(debugEnabled) log.debug("Found thermostat with ID: ${stat.id}")
                
                //Check for Multiple Zones
                def dni = getDeviceNetworkId(stat.id)
                def device = null;
                if(stat.zones.size > 1) {
                    stat.zones.each {
                        dni = getDeviceNetworkId(stat.id + "_" + it.id)
                        device = addMultipleDevices(dni, it.name)
                    }
                }
                else {
                    dni = getDeviceNetworkId(stat.id)
                    device = addMultipleDevices(dni, stat.name)
                }
                if(device) device.initialize()
                return device
            }
        } else {
            if(debugEnabled) log.debug("No thermostats returned from ${state.thermostatsPath}; trying Trane Home diagnostics thermostats")
            devices = addDiagnosticDevices()
        }

        log.info("Discovered ${devices.size()} thermostat(s)")
    }
}

private searchForClimate(httpNode) {
    if(httpNode != null && !(httpNode instanceof String)) {
        // Trane Home stores newer thermostat URLs in data-* attributes such as
        // data-event-url and data-edit-url, not as normal href links.
        httpNode.attributes()?.each { attrName, attrValue ->
            if(attrValue != null) {
                searchForDiagnosticThermostat(attrValue.toString())
            }
        }

        def href = httpNode.attributes()["href"]
        if(href != null) {
            if(href.matches("/houses/(?i).*climate"))
            {
                state.thermostatsPath = href.replace("/climate", "/xxl_thermostats")
                state.zonesPath = href.replace("/climate", "/xxl_zones")
                if(debugEnabled) log.debug("state.thermostatsPath = ${state.thermostatsPath}; state.zonesPath = ${state.zonesPath}")
            }
        }
        if(httpNode.children() != null) {
            httpNode.children().each {
                if(it!=null)
                    searchForClimate(it)
            }
        }
    }
}

private searchForDiagnosticThermostat(String href) {
    // Example match:
    // /houses/{houseId}/diagnostics/thermostats/{thermostatSerial}
    def matcher = href =~ "\\/houses\\/(\\d+)\\/diagnostics\\/thermostats\\/([^\\/\\?\\.]+)"
    if(matcher.find()) {
        def thermId = matcher.group(2).toString()
        def isNewThermostat = !state.diagnosticThermostats?.containsKey(thermId)
        state.diagnosticThermostats[thermId] = [
            id: thermId,
            houseId: matcher.group(1),
            path: "/houses/${matcher.group(1)}/diagnostics/thermostats/${thermId}.json",
            updatePath: "/houses/${matcher.group(1)}/diagnostics/thermostats/${thermId}"
        ]
        if(debugEnabled && isNewThermostat) log.debug("Found diagnostics thermostat ${thermId}")
    }
}

private discoverDiagnosticThermostats() {
    if(!state.thermostatsPath) {
        return
    }

    // Re-read the climate page after login so we can inspect its DOM attributes
    // and collect diagnostics thermostat URLs for newer Trane Home devices.
    def climatePath = state.thermostatsPath.replace("/xxl_thermostats", "/climate")
    def climateParams = [
        uri: serverUrl,
        path: climatePath,
        headers: getDefaultHeaders()
    ]

    try {
        httpGet(climateParams) { climateResp ->
            if(climateResp.status == 200 && climateResp.data) {
                climateResp.data[0].children().each {
                    searchForClimate(it)
                }
            }
        }
    }
    catch(e) {
        log.warn("Caught exception discovering diagnostics thermostats ${e}")
    }

    if(debugEnabled && (!state.diagnosticThermostats || state.diagnosticThermostats.size() == 0)) {
        log.debug("No diagnostics thermostat IDs discovered from climate page attributes.")
    }
}

private addDiagnosticDevices() {
    def devices = []
    state.diagnosticThermostats?.each { thermId, therm ->
        requestDiagnosticThermostat(thermId) { stat ->
            stat.zones.each { zone ->
                // Include the zone ID so multi-zone systems create one child per zone.
                def dni = getDeviceNetworkId("${thermId}_${zone.zone_id}")
                def label = stat.name ?: zone.name ?: thermId
                def device = addMultipleDevices(dni, label)
                if(device) {
                    device.initialize()
                    devices << device
                }
            }
        }
    }
    return devices
}

private def addMultipleDevices(dni, statname) {
    def device = getChildDevice(dni)
    if(!device) {
        try {
            device = addChildDevice(childNamespace, childName, dni, [ label: "${childName} (${statname})" ])
            if(device) {
                log.info("Created child device ${device.displayName}")
                if(debugEnabled) log.debug("Created ${device.displayName} with device network id: ${dni}")
            } else {
                log.error("Child device creation returned no device for ${statname}")
            }
        }
        catch(e) {
            log.error("Failed to create child device ${statname}: ${e}")
        }
    } else {
        log.info("Child device already exists: ${device.displayName}")
        if(debugEnabled) log.debug("Found already existing ${device.displayName} with device network id: ${dni}")
    }
    return device
}

private refreshCsrfToken(resp) {
    def respData = resp.data[0]

    // Get CSRF token from response
    // head / <meta name="csrf-token" content="***" />
    respData.children[0].children().each {
        if (it.attributes()["name"] == "csrf-token") {
            state.csrfToken = it.attributes()["content"]
            if(debugEnabled) log.debug("CSRF token refreshed")
        }
    }
}

private searchForAuthToken(httpNode) {
    if(httpNode != null && !(httpNode instanceof String)) {
        if (httpNode.attributes()["name"] != null) {
            if(httpNode.attributes()["name"]=="authenticity_token") {
                state.AuthToken = httpNode.attributes()["value"]
                if(debugEnabled) log.debug("Authenticity token found")
            }
        }
        
        if (httpNode.children() != null) {
            httpNode.children().each {
                if(it != null)
                    searchForAuthToken(it)
            }
        }
    }
}

private String getDeviceNetworkId(def statId) {
    return [ app.id, statId ].join('.')
}

private updateCookies(response) {
    response.getHeaders('Set-Cookie').each {
        def cookieValue = it.value.split(';')[0]
        def cookieName = cookieValue.split('=')[0]
        state.cookies[(cookieName)] = cookieValue
        if(debugEnabled) log.debug("Cookie updated: ${cookieName}")
    }
}

def getDefaultHeaders() {
    def headers = [
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Encoding': 'gzip, deflate',
        'Accept-Language': 'en-US,en,q=0.8',
        'Cache-Control': 'max-age=0',
        'Connection': 'keep-alive',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36',
        'X-CSRF-Token': state.csrfToken,
		'X-Requested-With': 'XMLHttpRequest'
    ]

    def cookieString = state.cookies?.collect { entry -> entry.value }?.join('; ');
    if (cookieString) { headers.Cookie = cookieString }
    return headers
}

private refreshAuthToken() {
    if(debugEnabled) log.debug("refreshAuthToken()")
    log.info("Attempting Trane Home login")

    // Initialize / clear any existing cookies
    state.cookies = [:]

    def loginParams = [
        //method: 'GET',
        uri: serverUrl,
        path: "/login",
        headers: getDefaultHeaders()
    ]
    
    try {
         httpGet(loginParams) { loginResp ->
            updateCookies(loginResp)
            // html / body   / div id=content / div id=external-wrapper / div id=external-content / div id=login-form / form / div / input name=authenticity_token
            // OLD def authenticityToken = loginResp.data[0].children[1].children[1].children[0].children[0].children[1].children[2].children[0].children[1].attributes()["value"]
            //def authenticityToken = loginResp.data[0].children[1].children[1].children[0].children[0].children[0].children[0].children[2].children[0].children[1].attributes()["value"]
            // Recursive search for authenticity token.  Should be more robust to Nexia DOM changes
            searchForAuthToken(loginResp.data[0])
            def authenticityToken = state.AuthToken
            refreshCsrfToken(loginResp);
            
            def sessionParams = [
                //method: 'POST',
                uri: serverUrl,
                path: '/session',
                requestContentType: 'application/x-www-form-urlencoded',
                headers: getDefaultHeaders(),
                body: [
                    'utf8': '✓',
                    'authenticity_token': authenticityToken,
                    'login': settings.username,
                    'password': settings.password
                ]
            ]

            httpPost(sessionParams) { sessionResp ->
                if (sessionResp.status != 302) {
                	log.error("Did not receive expected response status code.  Expected 302, actual ${sessionResp.status}")
                } else {
                    log.info("Trane Home login successful")
                }
                updateCookies(sessionResp)
                refreshCsrfToken(sessionResp);
            }
        }
    }
    catch(e) {
        log.error("Caught exception refreshing auth token ${e}")
    }
}

private requestThermostats(Closure closure) {
    if(debugEnabled) log.debug("requestThermostats(${state.thermostatsPath})")
    
    def thermostatsParams = [
        uri: serverUrl,
        path: state.thermostatsPath,
        headers: getDefaultHeaders()
    ]
    
    try {
        httpGet(thermostatsParams) { resp ->
            if (resp.status == 200) {
                closure(resp)
            } else if (resp.status == 302) { // Redirect to login page due to session expiration
                refreshAuthToken()
                requestThermostats(closure)
            } else {
                log.error("Unexpected status while requesting thermostats: ${resp.status}")
            }
        }
    }
    catch(e) {
        log.error("Caught exception requesting thermostats ${e}")
    }
}

private requestThermostat(deviceNetworkId, Closure closure) {
    if(debugEnabled) log.debug("requestThermostat(${deviceNetworkId})")
    requestThermostats { resp ->
        def stat = resp.data.find { it -> getDeviceNetworkId(it.id) == deviceNetworkId }
        if (!stat) {
            log.error("Device connection removed? No data found for ${deviceNetworkId} after polling")
        } else {
            closure(stat)
        }
    }
}

private requestDiagnosticThermostat(thermId, Closure closure) {
    def thermostat = state.diagnosticThermostats?.get(thermId)
    if(!thermostat) {
        log.error("No diagnostics thermostat configured for ${thermId}")
        return
    }

    def requestParams = [
        uri: serverUrl,
        path: thermostat.path,
        headers: getDefaultHeaders()
    ]

    try {
        httpGet(requestParams) { resp ->
            if(resp.status == 200) {
                closure(resp.data)
            } else if(resp.status == 302) {
                refreshAuthToken()
                requestDiagnosticThermostat(thermId, closure)
            } else {
                log.error("Unexpected status while requesting diagnostics thermostat ${thermId}: ${resp.status}")
            }
        }
    }
    catch(e) {
        log.error("Caught exception requesting diagnostics thermostat ${thermId} ${e}")
    }
}

private String getRawDeviceId(String deviceNetworkId) {
    // Hubitat child DNI is "{app.id}.{thermostatSerial}_{zoneId}".
    // Trane endpoints only want "{thermostatSerial}", so strip the app prefix
    // before deciding whether this is a diagnostics thermostat.
    def prefix = "${app.id}."
    if(deviceNetworkId?.startsWith(prefix)) {
        return deviceNetworkId.substring(prefix.length())
    }

    def dotIndex = deviceNetworkId?.indexOf(".")
    if(dotIndex != null && dotIndex > 0 && deviceNetworkId.substring(0, dotIndex).isInteger()) {
        return deviceNetworkId.substring(dotIndex + 1)
    }

    return deviceNetworkId
}

private boolean isDiagnosticChild(child) {
    def rawDeviceId = getRawDeviceId(child.device.deviceNetworkId)
    def thermId = rawDeviceId.split('_')[0].toString()
    return state.diagnosticThermostats?.get(thermId) != null
}

private Map getDiagnosticChildParts(child) {
    // After removing the Hubitat app prefix, the remaining ID is
    // "{thermostatSerial}_{zoneId}".
    def rawDeviceId = getRawDeviceId(child.device.deviceNetworkId)
    def parts = rawDeviceId.split('_')
    return [
        thermostatId: parts[0],
        zoneId: parts.size() > 1 ? parts[1] : "1"
    ]
}

private warnDiagnosticsWriteUnsupported(child, action) {
    log.warn("${action} is not supported yet for Trane Home diagnostics thermostat ${child.device.displayName}. Need the newer Trane Home write endpoint.")
}

private updateDiagnosticThermostat(child, settingName, value) {
    // The Trane Home climate page writes all supported controls through the
    // same endpoint using form fields like faceplate_thermostat[heat_setpoint].
    def parts = getDiagnosticChildParts(child)
    def thermostat = state.diagnosticThermostats?.get(parts.thermostatId)
    if(!thermostat) {
        log.error("No diagnostics thermostat configured for ${parts.thermostatId}")
        return
    }

    def requestParams = [
        uri: serverUrl,
        path: thermostat.updatePath,
        requestContentType: "application/x-www-form-urlencoded",
        headers: getDefaultHeaders(),
        body: [
            "faceplate_thermostat[${settingName}]": value,
            "zone_id": parts.zoneId
        ]
    ]

    try {
        httpPut(requestParams) { resp ->
            if(resp.status in [200, 204]) {
                if(debugEnabled) log.debug("Diagnostics thermostat ${settingName} update succeeded")
            } else if(resp.status == 302) {
                refreshAuthToken()
                updateDiagnosticThermostat(child, settingName, value)
            } else {
                log.error("Unexpected status while updating diagnostics thermostat ${settingName}: ${resp.status}")
            }
        }
    }
    catch(e) {
        log.error("Caught exception updating diagnostics thermostat ${settingName} ${e}")
    }
}

private String normalizeThermostatValue(value) {
    return value?.toString()?.toLowerCase()
}

private Integer toIntegerValue(value) {
    if(value == null || value == "") {
        return null
    }
    return value.toString().toBigDecimal().toInteger()
}

private String mapDiagnosticsOperatingState(stat, zone) {
    def stateValue = stat.operating_state ?: zone.status
    def operatingStateMapping = [
        "System Idle": "idle",
        "Idle": "idle",
        "Heating": "heating",
        "Cooling": "cooling",
        "Fan Running": "fan only",
        "Fan On": "fan only",
        "Fan Off": "idle"
    ]
    return operatingStateMapping[stateValue] ?: normalizeThermostatValue(stateValue)
}

private pollDiagnosticChild(child) {
    def rawDeviceId = getRawDeviceId(child.device.deviceNetworkId)
    def thermId = rawDeviceId.split('_')[0]
    def zoneId = rawDeviceId.split('_').size() > 1 ? rawDeviceId.split('_')[1] : null
    def statData = [:]

    if(debugEnabled) log.debug("pollDiagnosticChild(${thermId}, ${zoneId})")

    requestDiagnosticThermostat(thermId) { stat ->
        def zone = zoneId ? stat.zones.find { it.zone_id.toString() == zoneId.toString() } : stat.zones[0]
        if(!zone) {
            log.error("No diagnostics zone ${zoneId} found for thermostat ${thermId}")
            return
        }

        // Convert the diagnostics JSON shape into the same attribute names the
        // existing child driver already expects from parent.pollChild(this).
        def thermostatMode = normalizeThermostatValue(zone.mode)
        def heatingSetpoint = toIntegerValue(zone.heat_setpoint)
        def coolingSetpoint = toIntegerValue(zone.cool_setpoint)

        statData = [
            temperature: toIntegerValue(zone.temperature),
            heatingSetpoint: heatingSetpoint,
            coolingSetpoint: coolingSetpoint,
            minHeatingSetpoint: toIntegerValue(stat.min_heat_setpoint),
            maxHeatingSetpoint: toIntegerValue(stat.max_heat_setpoint),
            minCoolingSetpoint: toIntegerValue(stat.min_cool_setpoint),
            maxCoolingSetpoint: toIntegerValue(stat.max_cool_setpoint),
            thermostatSetpoint: (thermostatMode == "cool") ? coolingSetpoint : heatingSetpoint,
            thermostatMode: thermostatMode,
            thermostatFanMode: normalizeThermostatValue(stat.fan_mode),
            thermostatOperatingState: mapDiagnosticsOperatingState(stat, zone),
            systemStatus: stat.operating_state,
            activeMode: thermostatMode,
            emergencyHeatSupported: false,
            compressorSpeed: toIntegerValue(stat.compressor_speed),
            humidity: toIntegerValue(zone.humidity),
            outdoorTemperature: toIntegerValue(stat.outdoor_temperature),
            setpointStatus: zone.run_mode
        ]
    }

    return statData
}

// Poll Child is invoked from the Child Device itself as part of the Poll Capability
def pollChild(child) {
    // Keep the original SmartThings/Nexia path for older devices, but use the
    // diagnostics JSON endpoint when this child was created from a diagnostics URL.
    def rawDeviceId = getRawDeviceId(child.device.deviceNetworkId)
    def diagnosticThermId = rawDeviceId.split('_')[0]
    if(state.diagnosticThermostats?.containsKey(diagnosticThermId)) {
        return pollDiagnosticChild(child)
    }

    //if zoned, take off zone id... performs a repetitive update due to zoning, fix later
    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("ZoneBool ${zonedBool} pollChild(${deviceNetworkId})")

    def statData = [:]

    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        
        def systemStatusToOperatingStateMapping = [
            "System Idle": "idle",
            "Waiting...": "pending ${zone.zone_mode.toLowerCase()}",
            "Heating": "heating",
            "Cooling": "cooling",
            "Fan Running": "fan only"
        ]
        if(debugEnabled) log.debug "Zone: $zone"

        statData = [
            temperature: zone.temperature.toInteger(),
            heatingSetpoint: zone.heating_setpoint.toInteger(),
            coolingSetpoint: zone.cooling_setpoint.toInteger(),
            thermostatSetpoint: ((zone.zone_mode == "COOL") ? zone.cooling_setpoint : zone.heating_setpoint).toInteger(),
            // TODO: handle case for "emergency heat"
            thermostatMode: zone.requested_zone_mode.toLowerCase(), // "auto" "emergency heat" "heat" "off" "cool"
            thermostatFanMode: stat.fan_mode,  // "auto" "on" "circulate"
            thermostatOperatingState: systemStatusToOperatingStateMapping[stat.system_status], // "heating" "idle" "pending cool" "vent economizer" "cooling" "pending heat" "fan only"
            systemStatus: stat.system_status,
            activeMode: zone.zone_mode.toLowerCase(),
            emergencyHeatSupported: stat.emergency_heat_supported,
            humidity: (stat.current_relative_humidity * 100).toInteger(),
            outdoorTemperature: stat.raw_outdoor_temperature.toInteger(),
            setpointStatus: zone.setpoint_status
        ]
    }
    
    return statData
}

// updateType can be: "setpoints", "zone_mode"
private updateZone(zone, updateType) {
    if(debugEnabled) log.debug("updateZone(${zone.id}, ${updateType})")
    
    zone.hold_time = zone.hold_time.toBigInteger()
    
    def requestParams = [
        uri: serverUrl,
        path: "${state.zonesPath}/${zone.id}/${updateType}",
        headers: getDefaultHeaders(),
        body: zone
    ]

    httpPutJson(requestParams) { resp ->
        if (resp.status == 200) {
            if(debugEnabled) log.debug("Zone update suceeded")
        } else {
        	log.error("Unexpected status while attempting to update zone: ${resp.status}")
            
            /*
            def zoneJson = new org.json.JSONObject(zone).toString()
            def interations = Math.ceil(zoneJson.length() / 1200.0)
            for(int i = 0; i <= interations; i++) {
            	def end = i * 1200 + 1200
                if (zoneJson.length() < end) {
                	end = zoneJson.length()
                }
                if(debugEnabled) log.debug "${i}: ${zoneJson.substring(i * 1200, end)}"
            }
            */
        }
    }
}

// updateType can be: "fan_mode"
private updateThermostat(stat, updateType) {
    if(debugEnabled) log.debug("updateThermostat(${stat.id}, ${updateType})")
    def requestParams = [
        uri: serverUrl,
        path: "${state.thermostatsPath}/${stat.id}/${updateType}",
        headers: getDefaultHeaders(),
        body: stat
    ]

    httpPutJson(requestParams) { resp ->
        if (resp.status == 200) {
            if(debugEnabled) log.debug("Thermostat update suceeded")
        } else {
            log.error("Unexpected status while attempting to update thermostat: ${resp.status} ${stat}")
        }
    }
}

def setHeatingSetpoint(child, degreesF) {
    if(isDiagnosticChild(child)) {
        updateDiagnosticThermostat(child, "heat_setpoint", degreesF)
        return
    }

    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setHeatingSetpoint(${deviceNetworkId}, ${degreesF})")
    
    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        zone.heating_setpoint = degreesF
        zone.heating_integer = "${degreesF.toInteger()}"
        zone.heating_decimal = ""
        zone.cooling_setpoint = zone.cooling_setpoint
        zone.cooling_integer = "${zone.cooling_setpoint}"
        zone.cooling_decimal = ""
        
        updateZone(zone, "setpoints")
    }
}

def setCoolingSetpoint(child, degreesF) {
    if(isDiagnosticChild(child)) {
        updateDiagnosticThermostat(child, "cool_setpoint", degreesF)
        return
    }

    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setCoolingSetpoint(${deviceNetworkId}, ${degreesF})")
    
    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        zone.heating_setpoint = zone.heating_setpoint
        zone.heating_integer = "${zone.heating_setpoint.toInteger()}"
        zone.heating_decimal = ""
        zone.cooling_setpoint = degreesF
        zone.cooling_integer = "${degreesF.toInteger()}"
        zone.cooling_decimal = ""
        
        updateZone(zone, "setpoints")
    }
}

def setThermostatMode(child, value) {
    if(isDiagnosticChild(child)) {
        updateDiagnosticThermostat(child, "mode", value.toLowerCase())
        return
    }

    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setThermostatMode(${deviceNetworkId}, ${value})")
    
    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        zone.requested_zone_mode = value.toUpperCase()
        zone.last_requested_zone_mode = value.toUpperCase()
        updateZone(zone, "zone_mode")
    }
}

def setHoldMode(child, value) {//"permanent_hold" or "return_to_schedule"
    if(isDiagnosticChild(child)) {
        def runMode = (value == "return_to_schedule") ? "schedule" : "hold"
        updateDiagnosticThermostat(child, "run_mode", runMode)
        return
    }

    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setThermostatMode(${deviceNetworkId}, ${value})")
    
    requestThermostat(deviceNetworkId) { stat ->
        def zone = stat.zones[0]
        if(zonedBool > 1) {
            def zoneNetworkId = ((child.device.deviceNetworkId).split('_'))[1]
            zone = stat.zones.find {it.id == zoneNetworkId.toInteger()}
        }
        
        updateZone(zone, value)
    }
}

def setThermostatFanMode(child, value) {
    if(isDiagnosticChild(child)) {
        updateDiagnosticThermostat(child, "fan_mode", value.toLowerCase())
        return
    }

    def deviceNetworkId = ((child.device.deviceNetworkId).split('_'))[0]
    def zonedBool = ((child.device.deviceNetworkId).split('_')).size()
    if(debugEnabled) log.debug("setThermostatFanMode(${deviceNetworkId}, ${value})")
    
    requestThermostat(deviceNetworkId) { stat ->
        stat.fan_mode = value
        updateThermostat(stat, "fan_mode")
    }
}

void logsOff(){
    app.updateSetting("debugEnabled",[value:"false",type:"bool"])
}
