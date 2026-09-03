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
static String version()	{  return '1.1.2' }

definition(
    name: "Nexia Thermostat Manager",
    namespace: "trentfoley",
    author: "Trent Foley",
    description: "Connect your Nexia thermostat to Hubitat.",
    category: "Convenience",
    menu: "Integrations",
	importUrl:"https://raw.githubusercontent.com/waterboysh/hubitat/testing/trane-home-thermostat/nexiaThermostatManager.groovy",    
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
        if(!refreshAuthToken()) validationFailure("Login did not complete")
        stage = "authenticated home GET"
        state.thermostatsPath = null
        state.zonesPath = null
        state.diagnosticThermostats = [:]
        boolean homeVerified = false
        httpGet([uri: serverUrl, headers: getDefaultHeaders()]) { resp ->
            def data = responseData(stage, resp)
            requireStatus(resp, [200])
            def root = htmlRoot(data)
            searchForClimate(root)
            refreshCsrfToken(root)
            homeVerified = !!state.thermostatsPath || !!state.diagnosticThermostats
            debugStage(stage, "climate link present=${!!state.thermostatsPath}; diagnostics links=${state.diagnosticThermostats.size()}")
        }
        if(!homeVerified) {
            log.error("Authenticated home page could not be verified: no supported thermostat links found. Check web login and account access.")
            validationFailure("No supported thermostat links")
        }
        stage = "thermostat discovery"
        if(state.thermostatsPath) {
            // Diagnostics discovery is optional when legacy data is available.
            discoverDiagnosticThermostats()
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
            try { device.initialize() }
            catch(e) { logStageException("child initialize", e) }
        }
    }
    log.info("Validated ${candidates.size()} thermostat zone(s); ${ready} child device(s) available.")
}

private debugStage(String stage, String message) {
    if(debugEnabled) log.debug("[${stage}] ${message}")
}

private String dataType(value) {
    if(value == null) return "null"
    return value instanceof Class ? "Class<${value.name}>" : value.getClass().name
}

// Do not log bodies, request headers, credentials, cookies, tokens or full exception messages.
private responseData(String stage, resp) {
    debugStage(stage, "HTTP ${resp.status}; response type=${dataType(resp)}")
    if(debugEnabled) {
        try {
            def contentType = resp.getHeaders('Content-Type')?.find { true }?.value
            def mediaType = contentType?.toString()?.split(';')?.getAt(0)?.trim()
            // Log only a MIME type, never arbitrary response-header contents.
            if(mediaType && mediaType ==~ /[A-Za-z0-9.+-]+\/[A-Za-z0-9.+-]+/) {
                debugStage(stage, "content type=${mediaType}")
            }
        } catch(ignored) { /* Header diagnostics must never break a request. */ }
    }
    def data = resp.data
    debugStage(stage, "body type=${dataType(data)}")
    return data
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
    log.error("[${stage}] ${e.getClass().name}${line}. Response details are available with debug logging enabled.")
}

private htmlRoot(data, boolean required = true) {
    // Never index an unknown HTTP parser result (including an ExecutorHttpClient5 class).
    if(data instanceof groovy.util.slurpersupport.GPathResult) {
        if(data.size() > 0) return data[0]
    } else if(data instanceof groovy.util.Node) {
        return data
    } else if(data instanceof List && !data.isEmpty()) {
        return htmlRoot(data[0], required)
    }
    if(required) validationFailure("Expected parsed HTML")
    return null
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
    debugStage("thermostat validation", "route=${diagnostic ? 'diagnostics' : 'legacy'}; zones=${stat.zones.size()}; readings valid")
}

private boolean validIdentifier(value) {
    return (value instanceof Number || value instanceof CharSequence) && value.toString().trim().length() > 0
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
                debugStage("discovery", "Legacy climate route found")
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
        if(isNewThermostat) debugStage("discovery", "Diagnostics thermostat link found")
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
            def data = responseData("climate GET", climateResp)
            requireStatus(climateResp, [200])
            searchForClimate(htmlRoot(data))
        }
    }
    catch(e) {
        logStageException("climate GET / diagnostics discovery", e)
    }

    if(debugEnabled && (!state.diagnosticThermostats || state.diagnosticThermostats.size() == 0)) {
        log.debug("No diagnostics thermostat IDs discovered from climate page attributes.")
    }
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
                if(debugEnabled) log.debug("Created ${device.displayName} with device network id: ${dni}")
            } else {
                log.error("Child device creation returned no device for ${statname}")
            }
        }
        catch(e) {
            logStageException("child device creation (verify Nexia Thermostat driver is installed)", e)
        }
    } else {
        log.info("Child device already exists: ${device.displayName}")
        if(debugEnabled) log.debug("Found already existing ${device.displayName} with device network id: ${dni}")
    }
    return device
}

private refreshCsrfToken(root) {
    if(root == null || root instanceof String) return
    if(root.attributes()["name"] == "csrf-token") {
        state.csrfToken = root.attributes()["content"]
        debugStage("HTML tokens", "CSRF token present=${!!state.csrfToken}")
    }
    root.children()?.each { refreshCsrfToken(it) }
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
        httpGet([uri: serverUrl, path: "/login", headers: getDefaultHeaders()]) { resp ->
            def data = responseData(stage, resp)
            requireStatus(resp, [200])
            updateCookies(resp)
            stage = "login HTML parsing"
            def root = htmlRoot(data)
            searchForAuthToken(root)
            refreshCsrfToken(root)
            debugStage(stage, "authenticity token present=${!!state.AuthToken}; CSRF token present=${!!state.csrfToken}; cookies=${state.cookies.size()}")
            if(!state.AuthToken) validationFailure("Missing login authenticity token")
            stage = "session POST"
            httpPost([
                uri: serverUrl, path: "/session",
                requestContentType: "application/x-www-form-urlencoded",
                headers: getDefaultHeaders(),
                body: [utf8: '✓', authenticity_token: state.AuthToken,
                       login: settings.username, password: settings.password]
            ]) { sessionResp ->
                // Redirect responses need not contain parsed HTML. Do not index their bodies.
                debugStage(stage, "HTTP ${sessionResp.status}; response type=${dataType(sessionResp)}")
                requireStatus(sessionResp, [200, 302])
                updateCookies(sessionResp)
                submitted = true
                // Preserve token refresh when the session response has HTML, but
                // allow bodyless redirects. An unreadable optional body is diagnostic only.
                try {
                    def sessionRoot = htmlRoot(responseData("session response", sessionResp), false)
                    if(sessionRoot != null) refreshCsrfToken(sessionRoot)
                } catch(e) {
                    logStageException("optional session HTML", e)
                }
            }
        }
        if(submitted) debugStage("login", "Credentials submitted; authenticated page/data still requires verification")
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
            if(resp.status == 200) {
                if(!(data instanceof List)) validationFailure("Expected thermostat list")
                debugStage("legacy thermostats GET", "records=${data.size()}")
                // Supply the already-read data to avoid accessing the parser result twice.
                closure([status: 200, data: data])
                received = true
            } else if(resp.status in [302, 401] && !retried) {
                debugStage("legacy thermostats GET", "Session rejected; attempting one login retry")
                if(refreshAuthToken()) received = requestThermostats(closure, true)
            } else {
                log.error("Unexpected status while requesting thermostats: ${resp.status}")
            }
        }
    } catch(e) {
        logStageException("legacy thermostats GET / data processing", e)
        // Hubitat may throw for 401 instead of invoking the response callback.
        if(exceptionStatus(e) == 401 && !retried && refreshAuthToken()) {
            received = requestThermostats(closure, true)
        }
    }
    return received
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
            if(resp.status == 200) {
                if(!(data instanceof Map) || !(data.zones instanceof List)) {
                    validationFailure("Expected diagnostics object with zones")
                }
                debugStage("diagnostics thermostat GET", "zones=${data.zones.size()}")
                closure(data)
                received = true
            } else if(resp.status in [302, 401] && !retried) {
                debugStage("diagnostics thermostat GET", "Session rejected; attempting one login retry")
                if(refreshAuthToken()) received = requestDiagnosticThermostat(thermId, closure, true)
            } else {
                log.error("Unexpected status while requesting diagnostics thermostat: ${resp.status}")
            }
        }
    } catch(e) {
        logStageException("diagnostics thermostat GET / data processing", e)
        if(exceptionStatus(e) == 401 && !retried && refreshAuthToken()) {
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
            if(resp.status in [200, 204]) {
                if(debugEnabled) log.debug("Diagnostics thermostat ${settingName} update succeeded")
            } else if(resp.status in [302, 401] && !retried) {
                if(refreshAuthToken()) updateDiagnosticThermostat(child, settingName, value, true)
            } else {
                log.error("Unexpected status while updating diagnostics thermostat ${settingName}: ${resp.status}")
            }
        }
    }
    catch(e) {
        logStageException("diagnostics thermostat update", e)
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
        debugStage("legacy poll", "Zone selected; converting thermostat readings")

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
            log.error("Unexpected status while attempting to update thermostat: ${resp.status}")
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
