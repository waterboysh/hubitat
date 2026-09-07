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
 *	Date		  Who		    Description
 *	2022-09-15	  thebearmay	Port to Hubitat
 *	2022-09-16	  thebearmay	Fix thermostatOperatingMode
 *  2022-10-04    thebearmay    Add permanent hold and return to schedule
 *  2022-10-07    thebearmay    Option to use American Standard Login
 *  2026-06-04    Codex         Add Trane Home diagnostics support for newer thermostats
 *  2026-09-05    Codex         Improve login, thermostat discovery, and session recovery for older thermostats
 *
 */
static String version()	{  return '1.2.0' }

definition(
    name: "Nexia Thermostat Manager",
    namespace: "trentfoley",
    author: "Trent Foley",
    description: "Connect your Nexia thermostat to Hubitat.",
    category: "Convenience",
    menu: "Integrations",
	importUrl:"https://raw.githubusercontent.com/waterboysh/hubitat/main/trane-home-thermostat/nexiaThermostatManager.groovy",    
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
    }
}

def getChildNamespace() { "trentfoley" }
def getChildName() { "Nexia Thermostat" }
def getServerUrl() { 
    if(useAmerStand) 
        return "https://asairhome.com" 
    else
        return "https://www.tranehome.com" 
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    unschedule("logsOff")
    if(debugEnabled) runIn(1800, "logsOff")
    state.initializationReady = false
    debugStage("initialize", "version=${version()}; service=${useAmerStand ? 'American Standard' : 'Trane Home'}; debug auto-off=30 minutes")

    // Keep the previous routes available to existing children if setup fails.
    def previousRoutes = [
        thermostatsPath: state.thermostatsPath, zonesPath: state.zonesPath,
        diagnosticThermostats: state.diagnosticThermostats
    ]
    def candidates = []
    def stage = "login"
    try {
        state.thermostatsPath = null
        state.zonesPath = null
        state.diagnosticThermostats = [:]
        if(!authenticateAndDiscoverRoutes()) validationFailure("Login or dashboard discovery did not complete")
        stage = "thermostat discovery"
        if(state.thermostatsPath) {
            boolean received = requestThermostats { resp ->
                resp.data.each { stat ->
                    validateThermostat(stat, false)
                    if(stat.zones.size() > 1) {
                        stat.zones.each { zone ->
                            candidates << [dni: getDeviceNetworkId("${stat.id}_${zone.id}"), label: zone.name ?: stat.name ?: "Thermostat"]
                        }
                    } else {
                        candidates << [dni: getDeviceNetworkId(stat.id), label: stat.name ?: "Thermostat"]
                    }
                }
            }
            if(!received) validationFailure("Legacy request failed; not an empty result")
        }
        if(!candidates) {
            debugStage(stage, "legacy data empty or route absent; checking diagnostics")
            candidates = diagnosticDeviceCandidates()
        }
        if(!candidates) {
            log.error("No usable thermostat data returned. No child devices will be created.")
            validationFailure("No usable thermostat data")
        }
        if(candidates.collect { it.dni }.unique().size() != candidates.size()) {
            validationFailure("Duplicate thermostat/zone identifiers")
        }
        // All discovery responses have been validated before any child is created.
        state.initializationReady = true
    } catch(e) {
        logStageException(stage, e)
    }

    if(!state.initializationReady) {
        state.thermostatsPath = previousRoutes.thermostatsPath
        state.zonesPath = previousRoutes.zonesPath
        state.diagnosticThermostats = previousRoutes.diagnosticThermostats ?: [:]
        log.error("Setup stopped at ${stage}. No child devices were created; existing devices were left in place. Enable debug logging and save the app to capture another attempt.")
        return
    }
    int ready = 0
    candidates.each { candidate ->
        def device = addMultipleDevices(candidate.dni, candidate.label)
        if(device) {
            ready++
        }
    }
    // Child initialize() immediately polls the parent. Deferring it allows the
    // newly authenticated cookies and routes to be persisted first.
    runIn(5, "initializeChildDevices")
    log.info("Validated ${candidates.size()} thermostat zone(s); ${ready} child device(s) available.")
}

def initializeChildDevices() {
    def children = getChildDevices() ?: []
    children.each { device ->
        try { device.initialize() }
        catch(e) { logStageException("child initialize", e) }
    }
}

private debugStage(String stage, String message) {
    if(debugEnabled) log.debug("[${stage}] ${message}")
}

private String dataShape(value) {
    if(value == null) return "null"
    if(value instanceof Map) return "map (${value.size()} entries)"
    if(value instanceof List) return "list (${value.size()} entries)"
    if(value instanceof CharSequence) return "text (${value.length()} characters)"
    if(value instanceof Number) return "number"
    if(value instanceof Boolean) return "boolean"
    // Hubitat forbids both java.lang.Class expressions and getClass() calls.
    return "unsupported object"
}

// Do not log bodies, request headers, credentials, cookies, tokens or full exception messages.
private responseData(String stage, resp) {
    return resp.data
}

private debugResponseDetails(String stage, resp, data) {
    debugStage(stage, "HTTP ${resp.status}; content type=${responseContentType(resp) ?: 'unknown'}; body shape=${dataShape(data)}")
}

private requireStatus(resp, List allowed) {
    if(!(resp.status in allowed)) validationFailure("Unexpected HTTP status ${resp.status}")
}

private validationFailure(String reason) {
    // Only developer-defined descriptions are logged, never server response text.
    log.error("[validation] ${reason}")
    throw new IllegalStateException(reason)
}

private Integer exceptionStatus(e) {
    try {
        def status = e.response?.status
        return status instanceof Number ? status.intValue() : null
    } catch(ignored) {
        return null
    }
}

private logStageException(String stage, e) {
    def status = exceptionStatus(e)
    if(status != null) log.error("[${stage}] HTTP ${status}")
    if(e instanceof groovy.lang.MissingMethodException) {
        debugStage(stage, "Missing method=${e.method}; argument values omitted")
    }
    def source = e.stackTrace?.find { it.fileName?.endsWith(".groovy") }
    def line = source ? "; Groovy line=${source.lineNumber}" : ""
    log.error("[${stage}] Request or data-processing exception${line}. Response details are available with debug logging enabled.")
}

private String readBodyText(data) {
    if(data == null) return null
    if(data instanceof CharSequence) return data.toString()
    try { return data.getText() }
    catch(ignored) { /* Not a text stream; try a safe conversion below. */ }
    try { return data.toString() }
    catch(ignored) { return null }
}

private String responseContentType(resp) {
    try {
        def direct = resp.contentType?.toString()
        if(direct) return direct.toLowerCase()
    } catch(ignored) { }
    try {
        def header = resp.getHeaders('Content-Type')?.find { true }?.value?.toString()
        return header?.toLowerCase()
    } catch(ignored) {
        return null
    }
}

private boolean isJsonResponse(resp) {
    return responseContentType(resp)?.contains("json")
}

private boolean looksUnauthenticated(resp) {
    return responseContentType(resp)?.contains("html")
}

private Map htmlAttributes(String tag) {
    def attributes = [:]
    def matcher = tag =~ /(?is)([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(["'])(.*?)\2/
    while(matcher.find()) attributes[matcher.group(1).toLowerCase()] = matcher.group(3)
    return attributes
}

private String htmlNamedAttribute(String body, String elementName, String attributeName) {
    if(!body) return null
    def tags = body =~ /(?is)<(?:input|meta)\b[^>]*>/
    while(tags.find()) {
        def attributes = htmlAttributes(tags.group())
        if(attributes.name == elementName) return attributes[attributeName.toLowerCase()]?.toString()
    }
    return null
}

private String httpGetText(String path, String stage) {
    String body = null
    try {
        def params = [uri: serverUrl, headers: getDefaultHeaders(), textParser: true]
        if(path != null) params.path = path
        httpGet(params) { resp ->
            requireStatus(resp, [200])
            updateCookies(resp)
            def data = resp.data
            body = readBodyText(data)
            if(!body) {
                debugResponseDetails(stage, resp, data)
                validationFailure("Empty text response during ${stage}")
            }
        }
    } catch(e) {
        logStageException(stage, e)
    }
    return body
}

private boolean discoverHouseRoutes() {
    def previousRoutes = [thermostatsPath: state.thermostatsPath, zonesPath: state.zonesPath,
                          diagnosticThermostats: state.diagnosticThermostats]
    state.thermostatsPath = null
    state.zonesPath = null
    state.diagnosticThermostats = [:]
    def body = httpGetText("/", "authenticated dashboard GET")
    if(!body) {
        restoreRoutes(previousRoutes)
        return false
    }
    discoverRoutesFromText(body)
    boolean discovered = !!state.thermostatsPath || !!state.diagnosticThermostats
    debugStage("authenticated dashboard GET", "legacy route present=${!!state.thermostatsPath}; diagnostics links=${state.diagnosticThermostats?.size() ?: 0}")
    if(!discovered) {
        restoreRoutes(previousRoutes)
        log.error("No supported thermostat links found on the dashboard. Check Trane Home web access.")
    }
    return discovered
}

private restoreRoutes(Map routes) {
    state.thermostatsPath = routes.thermostatsPath
    state.zonesPath = routes.zonesPath
    state.diagnosticThermostats = routes.diagnosticThermostats ?: [:]
}

private discoverRoutesFromText(String body) {
    if(!body) return
    if(state.diagnosticThermostats == null) state.diagnosticThermostats = [:]

    def climate = body =~ /(?i)\/houses\/(\d+)\/climate/
    if(climate.find()) {
        def houseId = climate.group(1)
        state.thermostatsPath = "/houses/${houseId}/xxl_thermostats"
        state.zonesPath = "/houses/${houseId}/xxl_zones"
    }

    def diagnostic = body =~ /(?i)\/houses\/(\d+)\/diagnostics\/thermostats\/([^\/?\."'<>\s]+)/
    while(diagnostic.find()) {
        registerDiagnosticThermostat(diagnostic.group(1), diagnostic.group(2))
    }
}

private validateThermostat(stat, boolean diagnostic) {
    if(!(stat instanceof Map) || !(stat.zones instanceof List) || stat.zones.isEmpty()) {
        validationFailure("Expected thermostat object with zones")
    }
    if(!diagnostic && !validIdentifier(stat.id)) validationFailure("Missing thermostat ID")
    def zoneIds = []
    stat.zones.each { zone ->
        if(!(zone instanceof Map)) validationFailure("Expected zone object")
        def zoneId = diagnostic ? zone.zone_id : zone.id
        if(!validIdentifier(zoneId)) validationFailure("Missing zone ID")
        zoneIds << zoneId.toString()
        // Missing readings are not sufficient evidence of a usable device.
        def readings = diagnostic ? [zone.temperature, zone.heat_setpoint, zone.cool_setpoint] :
            [zone.temperature, zone.heating_setpoint, zone.cooling_setpoint]
        if(!readings.every { it != null && it.toString().isNumber() }) {
            validationFailure("Missing or invalid temperature/setpoint readings")
        }
    }
    if(zoneIds.unique().size() != stat.zones.size()) validationFailure("Duplicate zone IDs")
}

private boolean validIdentifier(value) {
    return (value instanceof Number || value instanceof CharSequence) && value.toString().trim().length() > 0
}

private registerDiagnosticThermostat(houseId, thermostatId) {
    def thermId = thermostatId.toString()
    state.diagnosticThermostats[thermId] = [
        id: thermId,
        houseId: houseId.toString(),
        path: "/houses/${houseId}/diagnostics/thermostats/${thermId}.json",
        updatePath: "/houses/${houseId}/diagnostics/thermostats/${thermId}"
    ]
}

private discoverDiagnosticThermostats() {
    if(!state.thermostatsPath) {
        return
    }

    // Re-read the climate page after login so we can inspect its DOM attributes
    // and collect diagnostics thermostat URLs for newer Trane Home devices.
    def climatePath = state.thermostatsPath.replace("/xxl_thermostats", "/climate")
    def climateBody = httpGetText(climatePath, "climate GET / diagnostics discovery")
    if(climateBody) discoverRoutesFromText(climateBody)

}

private diagnosticDeviceCandidates() {
    def candidates = []
    state.diagnosticThermostats?.each { thermId, therm ->
        boolean received = requestDiagnosticThermostat(thermId) { stat ->
            validateThermostat(stat, true)
            stat.zones.each { zone ->
                candidates << [dni: getDeviceNetworkId("${thermId}_${zone.zone_id}"),
                               label: stat.name ?: zone.name ?: "Thermostat"]
            }
        }
        if(!received) validationFailure("Diagnostics request failed")
    }
    return candidates
}

private def addMultipleDevices(dni, statname) {
    if(!state.initializationReady) {
        log.error("Child creation blocked: setup has not validated login and thermostat data.")
        return null
    }
    def device = getChildDevice(dni)
    if(!device) {
        try {
            device = addChildDevice(childNamespace, childName, dni, [ label: "${childName} (${statname})" ])
            if(device) {
                log.info("Created child device ${device.displayName}")
            } else {
                log.error("Child device creation returned no device for ${statname}")
            }
        }
        catch(e) {
            logStageException("child device creation (verify Nexia Thermostat driver is installed)", e)
        }
    } else {
    }
    return device
}

private String getDeviceNetworkId(def statId) {
    return [ app.id, statId ].join('.')
}

private updateCookies(response) {
    if(state.cookies == null) state.cookies = [:]
    response.getHeaders('Set-Cookie').each {
        def cookieValue = it.value.split(';')[0]
        def cookieName = cookieValue.split('=')[0]
        state.cookies[(cookieName)] = cookieValue
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

private boolean authenticateAndDiscoverRoutes() {
    if(!refreshAuthToken()) return false
    if(!discoverHouseRoutes()) return false
    // The climate page may contain diagnostics URLs not present on the dashboard.
    discoverDiagnosticThermostats()
    return !!state.thermostatsPath || !!state.diagnosticThermostats
}

private String responseLocation(resp) {
    try {
        def location = resp.getHeaders('Location')?.find { true }?.value?.toString()
        if(location) return location
    } catch(ignored) { }
    try {
        return resp.getHeaders()?.find { it.name?.toString()?.equalsIgnoreCase('Location') }?.value?.toString()
    } catch(ignored) {
        return null
    }
}

private boolean refreshAuthToken() {
    def stage = "login GET"
    boolean submitted = false
    state.cookies = [:]
    state.AuthToken = null
    state.csrfToken = null
    log.info("Attempting ${useAmerStand ? 'American Standard' : 'Trane Home'} login")
    try {
        if(!settings.username || !settings.password) {
            log.error("Login requires a username and password.")
            return false
        }
        def loginBody = httpGetText("/login", stage)
        if(!loginBody) return false
        stage = "login HTML parsing"
        state.AuthToken = htmlNamedAttribute(loginBody, "authenticity_token", "value")
        state.csrfToken = htmlNamedAttribute(loginBody, "csrf-token", "content")
        debugStage(stage, "Login form tokens found")
        if(!state.AuthToken) validationFailure("Missing login authenticity token")
        stage = "session POST"
        httpPost([
            uri: serverUrl, path: "/session",
            requestContentType: "application/x-www-form-urlencoded",
            headers: getDefaultHeaders(),
            body: [utf8: '✓', authenticity_token: state.AuthToken,
                   login: settings.username, password: settings.password]
        ]) { sessionResp ->
            requireStatus(sessionResp, [200, 302])
            updateCookies(sessionResp)
            def location = responseLocation(sessionResp)
            if(location?.toLowerCase()?.contains("/login")) {
                log.error("Trane Home rejected the login. Check the username, password, and web-account prompts.")
                submitted = false
            } else {
                submitted = true
            }
        }
        if(submitted) debugStage("login", "Credentials accepted; verifying dashboard access")
    } catch(e) {
        logStageException(stage, e)
    }
    return submitted
}

private boolean requestThermostats(Closure closure, boolean retried = false) {
    if(!state.thermostatsPath) {
        log.error("Thermostat request skipped: no discovered thermostat path.")
        return false
    }
    boolean received = false
    try {
        httpGet([uri: serverUrl, path: state.thermostatsPath, headers: getDefaultHeaders()]) { resp ->
            def data = responseData("legacy thermostats GET", resp)
            def unauthenticated = resp.status in [302, 401] || looksUnauthenticated(resp)
            if(resp.status == 200 && !unauthenticated) {
                if(!(data instanceof List)) {
                    debugResponseDetails("legacy thermostats GET", resp, data)
                    validationFailure("Expected thermostat list")
                }
                if(!state.initializationReady) debugStage("thermostat discovery", "legacy records=${data.size()}")
                // Supply the already-read data to avoid accessing the parser result twice.
                closure([status: 200, data: data])
                received = true
            } else if(unauthenticated && !retried) {
                debugStage("legacy thermostats GET", "Session appears expired; attempting one login and route-discovery retry")
                if(authenticateAndDiscoverRoutes()) received = requestThermostats(closure, true)
            } else {
                debugResponseDetails("legacy thermostats GET", resp, data)
                log.error("Unexpected response while requesting thermostats: status ${resp.status}; content type ${responseContentType(resp) ?: 'unknown'}")
            }
        }
    } catch(e) {
        logStageException("legacy thermostats GET / data processing", e)
        // Hubitat may throw for 401 instead of invoking the response callback.
        if(exceptionStatus(e) == 401 && !retried && authenticateAndDiscoverRoutes()) {
            received = requestThermostats(closure, true)
        }
    }
    return received
}

private requestThermostat(deviceNetworkId, Closure closure) {
    requestThermostats { resp ->
        def stat = resp.data.find { it -> getDeviceNetworkId(it.id) == deviceNetworkId }
        if (!stat) {
            log.error("Device connection removed? No data found for ${deviceNetworkId} after polling")
        } else {
            closure(stat)
        }
    }
}

private boolean requestDiagnosticThermostat(thermId, Closure closure, boolean retried = false) {
    def thermostat = state.diagnosticThermostats?.get(thermId)
    if(!thermostat) {
        log.error("No diagnostics route configured for the requested thermostat")
        return false
    }
    boolean received = false
    try {
        httpGet([uri: serverUrl, path: thermostat.path, headers: getDefaultHeaders()]) { resp ->
            def data = responseData("diagnostics thermostat GET", resp)
            def unauthenticated = resp.status in [302, 401] || looksUnauthenticated(resp)
            if(resp.status == 200 && !unauthenticated) {
                if(!(data instanceof Map) || !(data.zones instanceof List)) {
                    debugResponseDetails("diagnostics thermostat GET", resp, data)
                    validationFailure("Expected diagnostics object with zones")
                }
                if(!state.initializationReady) debugStage("thermostat discovery", "diagnostics zones=${data.zones.size()}")
                closure(data)
                received = true
            } else if(unauthenticated && !retried) {
                debugStage("diagnostics thermostat GET", "Session appears expired; attempting one login and route-discovery retry")
                if(authenticateAndDiscoverRoutes()) received = requestDiagnosticThermostat(thermId, closure, true)
            } else {
                debugResponseDetails("diagnostics thermostat GET", resp, data)
                log.error("Unexpected response while requesting diagnostics thermostat: status ${resp.status}; content type ${responseContentType(resp) ?: 'unknown'}")
            }
        }
    } catch(e) {
        logStageException("diagnostics thermostat GET / data processing", e)
        if(exceptionStatus(e) == 401 && !retried && authenticateAndDiscoverRoutes()) {
            received = requestDiagnosticThermostat(thermId, closure, true)
        }
    }
    return received
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

private updateDiagnosticThermostat(child, settingName, value, boolean retried = false) {
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
            def unauthenticated = resp.status in [302, 401] || looksUnauthenticated(resp)
            if(resp.status in [200, 204] && !unauthenticated) {
                if(debugEnabled) log.debug("Diagnostics thermostat ${settingName} update succeeded")
            } else if(unauthenticated && !retried) {
                if(authenticateAndDiscoverRoutes()) updateDiagnosticThermostat(child, settingName, value, true)
            } else {
                log.error("Unexpected response while updating diagnostics thermostat ${settingName}: status ${resp.status}; content type ${responseContentType(resp) ?: 'unknown'}")
            }
        }
    }
    catch(e) {
        logStageException("diagnostics thermostat update", e)
        if(exceptionStatus(e) == 401 && !retried && authenticateAndDiscoverRoutes()) {
            updateDiagnosticThermostat(child, settingName, value, true)
        }
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
private updateZone(zone, updateType, boolean retried = false) {
    if(debugEnabled) log.debug("updateZone(${zone.id}, ${updateType})")
    
    zone.hold_time = zone.hold_time.toBigInteger()
    
    def requestParams = [
        uri: serverUrl,
        path: "${state.zonesPath}/${zone.id}/${updateType}",
        headers: getDefaultHeaders(),
        body: zone
    ]

    try {
        httpPutJson(requestParams) { resp ->
            def unauthenticated = resp.status in [302, 401] || looksUnauthenticated(resp)
            if(resp.status == 200 && !unauthenticated) {
                if(debugEnabled) log.debug("Zone update succeeded")
            } else if(unauthenticated && !retried) {
                if(authenticateAndDiscoverRoutes()) updateZone(zone, updateType, true)
            } else {
	        	    log.error("Unexpected response while attempting to update zone: status ${resp.status}; content type ${responseContentType(resp) ?: 'unknown'}")
            
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
    } catch(e) {
        logStageException("zone update", e)
        if(exceptionStatus(e) == 401 && !retried && authenticateAndDiscoverRoutes()) {
            updateZone(zone, updateType, true)
        }
    }
}

// updateType can be: "fan_mode"
private updateThermostat(stat, updateType, boolean retried = false) {
    if(debugEnabled) log.debug("updateThermostat(${stat.id}, ${updateType})")
    def requestParams = [
        uri: serverUrl,
        path: "${state.thermostatsPath}/${stat.id}/${updateType}",
        headers: getDefaultHeaders(),
        body: stat
    ]

    try {
        httpPutJson(requestParams) { resp ->
            def unauthenticated = resp.status in [302, 401] || looksUnauthenticated(resp)
            if(resp.status == 200 && !unauthenticated) {
                if(debugEnabled) log.debug("Thermostat update succeeded")
            } else if(unauthenticated && !retried) {
                if(authenticateAndDiscoverRoutes()) updateThermostat(stat, updateType, true)
            } else {
                log.error("Unexpected response while attempting to update thermostat: status ${resp.status}; content type ${responseContentType(resp) ?: 'unknown'}")
            }
        }
    } catch(e) {
        logStageException("thermostat update", e)
        if(exceptionStatus(e) == 401 && !retried && authenticateAndDiscoverRoutes()) {
            updateThermostat(stat, updateType, true)
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
