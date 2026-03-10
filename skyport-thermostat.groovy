/*
 * Daikin Skyport Thermostat Driver
 * Skyport-based thermostat integration using the Daikin One Open API
 * (Integrator Cloud API)
 * https://www.daikinone.com/openapi/documentation/index.html
 *
 * Supports:
 * - Daikin One+, One Touch
 * - Amana Smart Thermostat
 * - Goodman GTST? (untested)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * Change History:
 *   Date         Who               What
 *   ----         ---               ----
 *   2022-07-03   Jean P. May, Jr.  Initial api.daikinskyport.com version
 *   2024-10      Tom Woodard       SkyPort Integrator API version
 *   2026-02-13   Trevor Deane      Multi-device support, async HTTP, proper initialization
 *   2026-03-10   Jon-Erik Lido     Token caching, user entered credentials, bug fixes,
 *                                    child outdoor temperature device, optimizations
 */
import java.text.SimpleDateFormat
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@SuppressWarnings('unused')
static String version() {return "1.0.0"}

@Field static final serverPath = "https://integrator-api.daikinskyport.com"

@Field static Map operatingModes = [
"0":"off",
"1":"heat",
"2":"cool",
"3":"auto",
"4":"emergency heat"
]

@Field static Map fanModes = [
"0":"auto",
"1":"on"
]

@Field static Map fanCirculateModes = [
"0":"off",
"1":"always on",
"2":"on a schedule"
]

metadata {
    definition (
        name: "Daikin SkyPort Thermostat", 
        namespace: "pw.lido", 
        author: "Jon-Erik Lido"
    ) {
        capability "Actuator"
        capability "Configuration"
        capability "Initialize"
        capability "Refresh"

        capability "Thermostat"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatFanMode"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatMode"
        capability "ThermostatOperatingState"
        
        attribute "equipmentStatus","number"
        attribute "fan","number"
        attribute "fanCirculateSpeed","string"
        attribute "setpointDelta","number"
        attribute "setpointMinimum","number"
        attribute "setpointMaximum","number"
        attribute "thermostatModeNum","number"
        attribute "heatingSetpoint","number"
        attribute "coolingSetpoint","number"
        
        attribute "tempOutdoor","number"
        attribute "humidity", "number"
        attribute "humidOutdoor", "number"
        attribute "geofencingEnabled", "string"
        attribute "deviceInitialized", "string"
        
        attribute "scheduleEnabled", "string"
        
        command "refresh"
        command "enableSchedule"
        command "disableSchedule"
        command "setThermostatMode", [[name: "Thermostat mode*",type:"ENUM", description:"Thermostat mode", constraints: operatingModes.collect {k,v -> v}]]
        command "setThermostatFanMode", [[name: "Fan mode*",type:"ENUM", description:"Fan mode", constraints: fanModes.collect {k,v -> v}]]
        command "saveCredentials", [[name:"apiKey", type:"STRING"], [name:"email", type:"STRING"], [name:"integratorToken", type:"STRING"]]
        command "clearCredentials"
    }   
}

preferences {
    input("thermostatName", "string", title: "Thermostat Name (from Daikin app)", description: "Select which thermostat this device controls", required: false)
    input("pollRate", "number", title: "Thermostat Polling Rate (minutes, 0=disabled)", defaultValue:5)
    input("debugEnabled", "bool", title: "Enable debug logging?")
}

@SuppressWarnings('unused')
def installed() {
    logTrace "${device.displayName} v${version()} installed()"
    initialize()
}

private boolean credentialsStored() {
    return state.daiApiKey?.trim() && 
           state.email?.trim() && 
           device.getDataValue("integratorToken")?.trim()
}

private boolean isTokenValid() {
    return (state.accessToken &&
            state.tokenExpiry &&
            now() < (state.tokenExpiry as Long))
}

private void cacheToken(tokenJson) {
    state.accessToken = tokenJson.accessToken
    // Cache at 90% of stated expiry to avoid edge-case expirations mid-flight
    state.tokenExpiry = now() + ((tokenJson.accessTokenExpiresIn as Long) * 900L)
    logDebug "Token cached — expires ${new Date(state.tokenExpiry as Long)}"
}

private Map buildAuthRequest() {
    String iToken = device.getDataValue("integratorToken")
    return [
        uri:                "${serverPath}/v1/token",
        requestContentType: 'application/json',
        contentType:        'application/json',
        timeout:            30,
        headers:            ['Content-Type': 'application/json',
                             'x-api-key':    state.daiApiKey],
        body:               JsonOutput.toJson([
                                email:           state.email,
                                integratorToken: iToken
                            ])
    ]
}

def initialize(){
    logDebug "initialize() - Getting device list from Daikin API"

    createOutdoorTemperatureSensor()

    // Preserve credentials across state reset
    def savedApiKey = state.daiApiKey
    def savedEmail  = state.email

    state.clear()

    // Restore credentials — the only state that survives initialize()
    state.daiApiKey       = savedApiKey
    state.email           = savedEmail

    // don't attempt to authenticate with empty credentials    
    if (credentialsStored()) {
        getAuthTokenAsync()
    } else {
        updateAttr("deviceInitialized", "Credentials not set — run saveCredentials command")
        logError "Credentials not configured. Use the saveCredentials command on the device page."
    }
}

private void createOutdoorTemperatureSensor() {
    String childDni = "${device.deviceNetworkId}-outdoor"
    if (!getChildDevice(childDni)) {
        addChildDevice(
            "hubitat",
            "Generic Component Temperature Sensor",
            childDni,
            [name: "${device.displayName} Outdoor Temperature", isComponent: true]
        )
        logDebug "Created outdoor temperature child device"
    }
}

void clearCredentials() {
    state.clear()
    unschedule("refresh")
    updateAttr("deviceInitialized", "Credentials cleared — run saveCredentials command")
    log.info "Credentials cleared. Run saveCredentials to reconfigure."
}

@SuppressWarnings('unused')
def updated(){
    logDebug "updated()"
    
    if (debugEnabled) {
        runIn(1800,"disableDebugLogging")
    } else {
        unschedule("disableDebugLogging")
    }
    
    unschedule("refresh")
    if ((pollRate ?: 5) > 0)
        schedule("0 0/${pollRate ?: 5} * * * ?", "refresh")

    // If device is configured, try to use it
    if (state.deviceId) {
        updateThermostat()
    }
}

@SuppressWarnings('unused')
def configure() {
    logDebug "configure()"
    initialize()
}

void updateAttr(String aKey, aValue, String aUnit = ""){
    sendEvent(name:aKey, value:aValue, unit:aUnit)
}

void logDebug(String msg){
    if(debugEnabled) {
        log.debug msg
    }
}

void logTrace(String msg){
    log.trace msg
}

void logError(String msg){
    log.error msg
}

/**
 * Get authentication token
 */
void getAuthTokenAsync() {
    logDebug "Getting auth token"
    asynchttpPost('handleAuthTokenResponse', buildAuthRequest())
}

void handleAuthTokenResponse(response, data) {
    if (response.status == 200) {
        try {
            cacheToken(response.json)
            getDeviceListAsync(state.accessToken)
        } catch (e) {
            logError "Error parsing auth response: $e"
            updateAttr("deviceInitialized", "Error: ${e.message}")
        }
    } else {
        logError "Failed to get auth token: ${response.status}"
        updateAttr("deviceInitialized", "Auth failed: ${response.status}")
    }
}

/**
 * Get list of available thermostats
 */
void getDeviceListAsync(String token) {
    if (!token) {
        logError "No access token available"
        return
    }
    
    logDebug "Getting device list"
    
    Map requestParams = [
        uri: "${serverPath}/v1/devices",
        requestContentType: 'application/json',
        contentType: 'application/json',
        timeout: 30,
        headers: [
            'Accept': 'application/json',
            'x-api-key': "${state.daiApiKey}",
            'Authorization': "Bearer ${token}"
        ]
    ]
    
    asynchttpGet('handleDeviceListResponse', requestParams)
}

void handleDeviceListResponse(response, data) {
    if (response.status == 200) {
        try {
            def jsonData = response.json
            logDebug "Device list response received"
            
            // Clear and initialize the map
            state.availableDevices = [:]
            
            // The response is a list with one element: [{devices: [...], locationName: "..."}]
            // We need to extract the first element first
            def locationData = null
            
            if (jsonData instanceof List && jsonData.size() > 0) {
                locationData = jsonData[0]
                logDebug "Extracted location data from list"
            } else if (jsonData.devices) {
                locationData = jsonData
                logDebug "Using response directly as location data"
            } else {
                logError "Cannot find devices in response"
                updateAttr("deviceInitialized", "Parse error: invalid structure")
                return
            }
            
            // Now get the devices array from the location data
            def devicesList = locationData.devices
            
            if (!devicesList) {
                logError "No devices array in location data"
                updateAttr("deviceInitialized", "Parse error: no devices array")
                return
            }
            
            logDebug "Found devices: ${devicesList.size()} items"
            
            // Now devicesList should be: [[{id, name, ...}, {id, name, ...}]]
            // We need to unwrap one more level if it's nested
            if (devicesList.size() > 0) {
                def firstItem = devicesList[0]
                
                // Check if firstItem is itself a list of devices
                if (firstItem instanceof List) {
                    logDebug "Devices are nested in a list, unwrapping..."
                    devicesList = firstItem
                }
            }
            
            logDebug "Processing ${devicesList.size()} devices"
            
            // Now iterate through actual devices
            for (int i = 0; i < devicesList.size(); i++) {
                def d = devicesList[i]
                
                if (d.id && d.name) {
                    String deviceId = d.id.toString()
                    String deviceName = d.name.toString()
                    state.availableDevices[deviceId] = deviceName
                    logDebug "Device ${i+1}: ${deviceName} (${deviceId})"
                } else {
                    logError "Device ${i} missing id or name: ${d}"
                }
            }
            
            logDebug "Stored ${state.availableDevices.size()} devices"
            
            if (state.availableDevices.size() > 0) {
                selectThermostatByName()
            } else {
                logError "No valid devices found"
                updateAttr("deviceInitialized", "No valid devices")
            }
            
        } catch (e) {
            logError "Error in handleDeviceListResponse: ${e.message}"
            updateAttr("deviceInitialized", "Parse error")
        }
    } else {
        logError "Failed to get device list: ${response.status}"
        updateAttr("deviceInitialized", "Device list failed: ${response.status}")
    }
}

void selectThermostatByName() {
    logDebug "Step 3: Selecting thermostat"
    logDebug "Available devices map: ${state.availableDevices}"
    
    if (!state.availableDevices || state.availableDevices.size() == 0) {
        logError "No devices available to select"
        return
    }
    
    // If user specified a name/ID preference, find it
    if (thermostatName && thermostatName != "") {
        logDebug "Looking for thermostat: '${thermostatName}'"
        
        // Search for the device by name or ID
        def foundId = null
        def foundName = null
        
        state.availableDevices.each { key, value ->
            logDebug "  Checking: key='${key}' value='${value}'"
            if (value == thermostatName || key == thermostatName) {
                foundId = key
                foundName = value
            }
        }
        
        if (foundId) {
            state.deviceId = foundId
            logDebug "✓ Selected thermostat: ${foundName} (${foundId})"
            updateAttr("deviceInitialized", "Connecting...")
            // Step 4: Get thermostat details
            fetchDeviceDetail(state.deviceId)
            return
        } else {
            logError "Thermostat '${thermostatName}' not found"
            logError "Available thermostats: ${state.availableDevices.values().join(', ')}"
            updateAttr("deviceInitialized", "Thermostat not found: ${thermostatName}")
            return
        }
    } else {
        // No preference set, use first device
        def firstEntry = state.availableDevices.find { true }
        if (firstEntry) {
            state.deviceId = firstEntry.key
            def firstName = firstEntry.value
            logDebug "✓ Using first thermostat: ${firstName} (${state.deviceId})"
            logDebug "To use a different thermostat, set 'Thermostat Name' in preferences"
            updateAttr("deviceInitialized", "Connecting...")
            
            // Step 4: Get thermostat details
            fetchDeviceDetail(state.deviceId)
        } else {
            logError "No devices available"
            updateAttr("deviceInitialized", "No devices available")
        }
    }
}

/**
 * Request thermostat details
 */
void fetchDeviceDetail(String deviceId) {
    if (isTokenValid()) {
        getDeviceDetailWithToken(state.accessToken, deviceId)
        return
    }
    logDebug "Token expired — refreshing before device detail"
    asynchttpPost('handleAccessTokenAndDeviceDetail', buildAuthRequest(), [deviceId: deviceId])
}

void handleAccessTokenAndDeviceDetail(response, data) {
    if (response.status == 200) {
        cacheToken(response.json)
        getDeviceDetailWithToken(state.accessToken, data.deviceId as String)
    } else {
        logError "Token refresh failed (device detail): ${response.status}"
    }
}

private void getDeviceDetailWithToken(String token, String deviceId) {
    logDebug "Fetching device detail for ${deviceId}"
    asynchttpGet('handleDeviceDetailResponse', [
        uri:                "${serverPath}/v1/devices/${deviceId}",
        requestContentType: 'application/json',
        contentType:        'application/json',
        timeout:            30,
        headers:            ['Accept':        'application/json',
                             'x-api-key':     state.daiApiKey,
                             'Authorization': "Bearer ${token}"]
    ])
}


void handleDeviceDetailResponse(response, data) {
    if (response.status == 200) {
        try {
            def devDetail = response.json
            logDebug "✓ Device details received"
			logDebug "RAW DEVICE DETAIL: ${groovy.json.JsonOutput.toJson(devDetail)}"      
            
            // Store firmware version
            if (devDetail.firmwareVersion) {
                device.updateDataValue("firmware", devDetail.firmwareVersion)
            }
            
            // Update all attributes
            updateThermostatAttributes(devDetail)
        } catch (e) {
            logError "Error parsing device detail: $e"
        }
    } else if (response.status == 408) {
        logError "Device detail request timeout (408) - retrying in 5 seconds"
        runIn(5, 'retryDeviceDetail')
    } else {
        logError "Failed to get device detail: ${response.status}"
        // Don't retry other errors, just show the status
    }
}

void retryDeviceDetail() {
    if (state.deviceId) {
        logDebug "Retrying device detail request"
        fetchDeviceDetail(state.deviceId)
    }
}

/**
 * Update attributes from device detail JSON
 */
void updateThermostatAttributes(Map devDetail) {
    logDebug "Updating thermostat attributes"
    
    def modeStr = ["off","heat","cool","auto","emergency heat"]
    def circStr = ["auto","on","circulate"]
    def fanSpd = ["low","medium","high"]
	def equipStatusMap = [1:"cooling", 2:"cooling", 3:"heating", 4:"fan only", 5:"idle"]
    
    def degUnit = "°C"
    def detail = devDetail.clone()
    
    // Store the original Celsius delta for calculations
    state.setpointDeltaCelsius = detail.setpointDelta.toFloat()
    
    // Convert to Fahrenheit if needed for display
    if (useFahrenheit()) {
        detail.setpointDelta = celsiusToFahrenheit(detail.setpointDelta.toFloat()).toFloat().round(0)
        detail.setpointMinimum = celsiusToFahrenheit(detail.setpointMinimum.toFloat()).toFloat().round(0)
        detail.heatSetpoint = celsiusToFahrenheit(detail.heatSetpoint.toFloat()).toFloat().round(0)
        detail.coolSetpoint = celsiusToFahrenheit(detail.coolSetpoint.toFloat()).toFloat().round(0)
        detail.setpointMaximum = celsiusToFahrenheit(detail.setpointMaximum.toFloat()).toFloat().round(0)
        detail.tempIndoor = celsiusToFahrenheit(detail.tempIndoor.toFloat()).toFloat().round(1)
        detail.tempOutdoor = celsiusToFahrenheit(detail.tempOutdoor.toFloat()).toFloat().round(1)
        degUnit = "°F"
    }
    
    try {
        updateAttr("thermostatModeNum", detail.mode.toInteger())
        updateAttr("thermostatMode", modeStr[detail.mode.toInteger()])
 		updateAttr("thermostatOperatingState", equipStatusMap[detail.equipmentStatus.toInteger()] ?: "idle")
        updateAttr("fan", detail.fan)
        updateAttr("thermostatFanMode", circStr[detail.fanCirculate.toInteger()])
        updateAttr("fanCirculateSpeed", fanSpd[detail.fanCirculateSpeed.toInteger()])
        updateAttr("setpointDelta", detail.setpointDelta, degUnit)
        updateAttr("setpointMinimum", detail.setpointMinimum, degUnit)
        updateAttr("heatingSetpoint", detail.heatSetpoint, degUnit)
        updateAttr("coolingSetpoint", detail.coolSetpoint, degUnit)
        updateAttr("setpointMaximum", detail.setpointMaximum, degUnit)
        updateAttr("temperature", detail.tempIndoor, degUnit)
        updateAttr("tempOutdoor", detail.tempOutdoor, degUnit)
        updateAttr("humidity", detail.humIndoor, "%")
        updateAttr("humidOutdoor", detail.humOutdoor, "%")
        updateAttr("geofencingEnabled", detail.geofencingEnabled ? "true" : "false")
        updateAttr("scheduleEnabled", detail.scheduleEnabled ? "true" : "false")
        updateAttr("deviceInitialized", "Connected")

        def childTemperature = getChildDevice("${device.deviceNetworkId}-outdoor")
        if (childTemperature) {
            childTemperature.parse([[name: "temperature", value: detail.tempOutdoor, unit: degUnit]])
        }
    } catch (e) {
        logError "Error updating attributes: $e"
    }
}

/*****************************
 * Begin Thermostat Methods **
 ****************************/ 

void refresh() {
    updateThermostat()
}

void componentRefresh(cd) {
    logDebug "componentRefresh called by child ${cd.displayName}"
    updateThermostat()
}

void updateThermostat() {
    if (!state.deviceId) {
        logError "updateThermostat: Device not configured"
        return
    }
    fetchDeviceDetail(state.deviceId)
}

void setMode(modeNum) {
    logDebug "setMode($modeNum)"
    if (!state.deviceId) {
        logError "setMode: Device not configured"
        return
    }
    
    def coolset = device.currentValue("coolingSetpoint")
    def heatset = device.currentValue("heatingSetpoint")
    
    if (useFahrenheit()) {
        coolset = fahrenheitToCelsius(coolset).toFloat().round(1)
        heatset = fahrenheitToCelsius(heatset).toFloat().round(1)
    }
    
    sendModeAndSetpoints([mode:modeNum, coolSetpoint:coolset, heatSetpoint:heatset])
}

void auto() {
    setMode(3)
    updateAttr("thermostatMode", "auto")
}

void cool() {
    setMode(2)
    updateAttr("thermostatMode", "cool")
}

void emergencyHeat() {
    setMode(4)
    updateAttr("thermostatMode", "emergency heat")
}

void fanAuto() {
    sendFanSettings([fanCirculate:0, fanCirculateSpeed:0])
    updateAttr("thermostatFanMode", "auto")
}

void fanCirculate() {
    sendFanSettings([fanCirculate:2, fanCirculateSpeed:0])
    updateAttr("thermostatFanMode", "circulate")
}

void fanOn() {
    sendFanSettings([fanCirculate:1, fanCirculateSpeed:0])
    updateAttr("thermostatFanMode", "on")
}

void heat() {
    setMode(1)
    updateAttr("thermostatMode", "heat")
}

void off() {
    setMode(0)
    updateAttr("thermostatMode", "off")
}

void setHeatingSetpoint(temp) {
    logDebug "setHeatingSetpoint($temp)"
    if (!state.deviceId) {
        logError "setHeatingSetpoint: Device not configured"
        return
    }
    
    if (device.currentValue("setpointMaximum") != null && temp > device.currentValue("setpointMaximum")) 
        temp = device.currentValue("setpointMaximum")
    if (device.currentValue("setpointMinimum") != null && temp < device.currentValue("setpointMinimum")) 
        temp = device.currentValue("setpointMinimum")
    
    def coolset = device.currentValue("coolingSetpoint")
    def mode = device.currentValue("thermostatModeNum")
    def delta = state.setpointDeltaCelsius ?: 2.0  // Use stored Celsius delta
    
    logDebug "Raw values - heat input: ${temp}, cool: ${coolset}, delta: ${delta}°C, mode: ${mode}, useFahrenheit: ${useFahrenheit()}"
    
    // Convert everything to Celsius for calculations
    if (useFahrenheit()) {
        logDebug "Converting from Fahrenheit to Celsius..."
        temp = fahrenheitToCelsius(temp).toFloat().round(1)
        coolset = fahrenheitToCelsius(coolset).toFloat().round(1)
        logDebug "After conversion - heat: ${temp}°C, cool: ${coolset}°C"
    } else {
        temp = normalizeTemp(temp)
        logDebug "Already in Celsius - heat: ${temp}°C, cool: ${coolset}°C"
    }
    
    // Validate/default values
    if (!mode) {
        mode = 3  // Default to auto
        logDebug "Mode was null, defaulting to 3 (auto)"
    }
    if (!coolset || coolset == 0) {
        coolset = (temp + delta).toFloat().round(1)
        logDebug "Cool setpoint was null/zero, defaulting to heat + delta: ${coolset}°C"
    }
    
    // Ensure cool is at least delta above heat
    if (coolset < (temp + delta)) {
        logDebug "Cool (${coolset}) < heat + delta (${temp + delta}), adjusting cool to ${temp + delta}"
        coolset = (temp + delta).toFloat().round(1)
    }
    
    logDebug "Final values for API - mode: ${mode}, heatSetpoint: ${temp}, coolSetpoint: ${coolset}"
    
    def bodyMap = [mode: mode.toInteger(), coolSetpoint: coolset.toFloat(), heatSetpoint: temp.toFloat()]
    logDebug "Sending PUT with body: ${bodyMap}"
    
    sendModeAndSetpoints(bodyMap)
}

void setCoolingSetpoint(temp) {
    logDebug "setCoolingSetpoint($temp)"
    if (!state.deviceId) {
        logError "setCoolingSetpoint: Device not configured"
        return
    }
    
    if (device.currentValue("setpointMaximum") != null && temp > device.currentValue("setpointMaximum")) 
        temp = device.currentValue("setpointMaximum")
    if (device.currentValue("setpointMinimum") != null && temp < device.currentValue("setpointMinimum")) 
        temp = device.currentValue("setpointMinimum")
    
    def heatset = device.currentValue("heatingSetpoint")
    def mode = device.currentValue("thermostatModeNum")
    def delta = state.setpointDeltaCelsius ?: 2.0  // Use stored Celsius delta
    
    logDebug "Raw values - cool input: ${temp}, heat: ${heatset}, delta: ${delta}°C, mode: ${mode}, useFahrenheit: ${useFahrenheit()}"
    
    // Convert everything to Celsius for calculations
    if (useFahrenheit()) {
        logDebug "Converting from Fahrenheit to Celsius..."
        temp = fahrenheitToCelsius(temp).toFloat().round(1)
        heatset = fahrenheitToCelsius(heatset).toFloat().round(1)
        logDebug "After conversion - cool: ${temp}°C, heat: ${heatset}°C"
    } else {
        temp = normalizeTemp(temp)
        logDebug "Already in Celsius - cool: ${temp}°C, heat: ${heatset}°C"
    }
    
    // Validate/default values
    if (!mode) {
        mode = 3  // Default to auto
        logDebug "Mode was null, defaulting to 3 (auto)"
    }
    if (!heatset || heatset == 0) {
        heatset = (temp - delta).toFloat().round(1)
        logDebug "Heat setpoint was null/zero, defaulting to cool - delta: ${heatset}°C"
    }
    
    // Ensure cool is at least delta above heat
    if (temp < (heatset + delta)) {
        logDebug "Cool (${temp}) < heat + delta (${heatset + delta}), adjusting cool to ${heatset + delta}"
        temp = (heatset + delta).toFloat().round(1)
    }
    
    logDebug "Final values for API - mode: ${mode}, heatSetpoint: ${heatset}, coolSetpoint: ${temp}"
    
    def bodyMap = [mode: mode.toInteger(), coolSetpoint: temp.toFloat(), heatSetpoint: heatset.toFloat()]
    logDebug "Sending PUT with body: ${bodyMap}"
    
    sendModeAndSetpoints(bodyMap)
}

void setThermostatFanMode(fanmode) {
    if (fanmode == "on") 
       fanOn()
    else if (fanmode == "circulate")
       fanCirculate()
    else
       fanAuto()    
}

void setThermostatMode(tmode) {
    switch (tmode) {
        case "auto":           auto();          break
        case "heat":           heat();          break
        case "cool":           cool();          break
        case "off":            off();           break
        case "emergency heat": emergencyHeat(); break
        default:
            logError "setThermostatMode: unrecognized mode '${tmode}' — no action taken"
            break
    }
}

void saveCredentials(String apiKey, String email, String token) {
    // Store in state — state supports arbitrary string length
    state.daiApiKey = apiKey
    state.email = email
    device.updateDataValue("integratorToken", token)
    log.info "Credentials saved. Run initialize() to connect."
}

void enableSchedule() {
    logDebug "enableSchedule()"
    if (!state.deviceId) {
        logError "enableSchedule: Device not configured"
        return
    }
    
    logDebug "Enabling schedule for device ${state.deviceId}"
    sendScheduleConfig([scheduleEnabled: true])
}

void disableSchedule() {
    logDebug "disableSchedule()"
    if (!state.deviceId) {
        logError "disableSchedule: Device not configured"
        return
    }
    
    logDebug "Disabling schedule for device ${state.deviceId}"
    sendScheduleConfig([scheduleEnabled: false])
}

/***************************
 * End Thermostat Methods **
 **************************/

// Mode + setpoints: PUT /v1/devices/{id}/msp
void sendModeAndSetpoints(Map bodyMap) {
    sendCommandInternal("/v1/devices/${state.deviceId}/msp", bodyMap)
}

// Schedule enable/disable: PUT /v1/devices/{id}/schedule
void sendScheduleConfig(Map bodyMap) {
    sendCommandInternal("/v1/devices/${state.deviceId}/schedule", bodyMap)
}

// Fan circulate (unitary only): PUT /v1/devices/{id}/fan
void sendFanSettings(Map bodyMap) {
    sendCommandInternal("/v1/devices/${state.deviceId}/fan", bodyMap)
}

private void sendCommandInternal(String endpoint, Map bodyMap) {
    if (isTokenValid()) {
        putCommand(state.accessToken, endpoint, bodyMap)
        return
    }
    logDebug "Token expired — refreshing before PUT"
    asynchttpPost('handleAccessTokenAndCommand', buildAuthRequest(),
                  [endpoint: endpoint, bodyMap: bodyMap])
}

void handleAccessTokenAndCommand(response, data) {
    if (response.status == 200) {
        cacheToken(response.json)
        putCommand(
            state.accessToken,
            data.endpoint as String,
            data.bodyMap as Map
        )
    } else {
        logError "Token refresh failed (command): ${response.status}"
    }
}


private void putCommand(String token, String endpoint, Map bodyMap) {
    asynchttpPut('handlePutResponse', [
        uri:                "${serverPath}${endpoint}",
        requestContentType: 'application/json',
        contentType:        'application/json',
        timeout:            30,
        headers:            ['Accept':        'application/json',
                             'x-api-key':     state.daiApiKey,
                             'Authorization': "Bearer ${token}"],
        body:               JsonOutput.toJson(bodyMap)
    ])
}

void handlePutResponse(response, data) {
    if (response.status == 200) {
        logDebug "✓ PUT request successful"
        // Refresh to get updated state - API says 15 secs to stabilize state
        runIn(15, 'updateThermostat')
    } else {
        logError "PUT request failed: ${response.status}"
    }
}

Float normalizeTemp(temp) {
    Float nTemp = ((int) (temp*2 + 0.5))/2.0
    return nTemp    
}

@SuppressWarnings('unused')
void disableDebugLogging() {
    logDebug "Disabling debug logging at ${new Date()}"
    device.updateSetting("debugEnabled",[value:"false",type:"bool"])
}

private boolean useFahrenheit() {
    return location.temperatureScale == "F"
}
