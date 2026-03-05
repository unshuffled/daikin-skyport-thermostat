/* 
 * Daikin SkyPort Thermostat Driver
 *
 * Uses the documented Daikin One Open API (Integrator Cloud API)
 * https://www.daikinone.com/openapi/documentation/index.html
 *
 * Supports:
 * - Daikin One+, One Touch
 * - Amana Smart Thermostat
 * - Goodman GTST? (untested)
 * 
 * Dev note: There is also a different (undocumented?) Consumer Skyport API, which a future driver
 * could choose to implement. Documentation examples:
 * https://github.com/apetrycki/daikinskyport/blob/master/API_info.md
 * https://github.com/TJCoffey/DaikinSkyportToMQTT/blob/main/ThermostatParameters.md
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
 *  Forked from: Trevor Deane's Daikin OnePlus Thermostat driver (version 0.2.0)
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
           state.integratorToken?.trim()
}

def initialize(){
    logDebug "initialize() - Getting device list from Daikin API"

    // Preserve credentials across state reset
    def savedApiKey       = state.daiApiKey
    def savedEmail        = state.email
    def savedToken        = state.integratorToken

    state.clear()

    // Restore credentials — the only state that survives initialize()
    state.daiApiKey       = savedApiKey
    state.email           = savedEmail
    state.integratorToken = savedToken

    // don't attempt to authenticate with empty credentials    
    if (credentialsStored()) {
        getAuthTokenAsync()
    } else {
        updateAttr("deviceInitialized", "Credentials not set — run saveCredentials command")
        logError "Credentials not configured. Use the saveCredentials command on the device page."
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
    
    if(debugEnabled) {
        runIn(1800,"logsOff")
    } else {
        unschedule("logsOff")
    }
    
    if(pollRate == null)
        device.updateSetting("pollRate",[value:5,type:"number"])
    if(pollRate > 0){
        runIn(pollRate*60,"refresh")
    } else {
        unschedule("refresh")
    }
    
    // If device is configured, try to use it
    if(state.deviceId) {
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
 * Step 1: Get authentication token
 */
void getAuthTokenAsync() {
    logDebug "Step 1: Getting auth token"
    
    Map requestParams = [
        uri: "${serverPath}/v1/token",
        requestContentType: 'application/json',
        contentType: 'application/json',
        timeout: 30,
        headers: [
            'Content-Type': 'application/json',
            'x-api-key': "${state.daiApiKey}"
        ],
        body: JsonOutput.toJson([
            email: "${state.email}",
            integratorToken: "${state.integratorToken}"
        ])
    ]
    
    asynchttpPost('handleAuthTokenResponse', requestParams)
}

void handleAuthTokenResponse(response, data) {
    if (response.status == 200) {
        try {
            def jsonData = response.json
            logDebug "✓ Auth token obtained (expires in ${jsonData.accessTokenExpiresIn}s)"
            
            // Use token inline without storing in variable
            getDeviceListAsync(jsonData.accessToken)
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
 * Step 2: Get list of available thermostats
 */
void getDeviceListAsync(String token) {
    if (!token) {
        logError "Step 2: No access token available"
        return
    }
    
    logDebug "Step 2: Getting device list"
    
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
            getDeviceDetailAsync(state.deviceId)
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
            getDeviceDetailAsync(state.deviceId)
        } else {
            logError "No devices available"
            updateAttr("deviceInitialized", "No devices available")
        }
    }
}

/**
 * Step 4: Get thermostat details
 */
void getDeviceDetailAsync(String deviceId) {
    logDebug "Getting fresh token for device detail"
    
    // Get fresh token first
    Map requestParams = [
        uri: "${serverPath}/v1/token",
        requestContentType: 'application/json',
        contentType: 'application/json',
        timeout: 30,
        headers: [
            'Content-Type': 'application/json',
            'x-api-key': "${state.daiApiKey}"
        ],
        body: JsonOutput.toJson([
            email: "${state.email}",
            integratorToken: "${state.integratorToken}"
        ])
    ]
    
    asynchttpPost('handleTokenForDeviceDetail', requestParams, [deviceId: deviceId])
}

void handleTokenForDeviceDetail(response, data) {
    if (response.status == 200) {
        try {
            def jsonData = response.json
            logDebug "Fresh token obtained for device detail"
            
            String deviceId = data.deviceId
            logDebug "Step 4: Getting device details for ${deviceId}"
            
            // Use token inline in request
            Map requestParams = [
                uri: "${serverPath}/v1/devices/${deviceId}",
                requestContentType: 'application/json',
                contentType: 'application/json',
                timeout: 30,
                headers: [
                    'Accept': 'application/json',
                    'x-api-key': "${state.daiApiKey}",
                    'Authorization': "Bearer ${jsonData.accessToken}"
                ]
            ]
            
            asynchttpGet('handleDeviceDetailResponse', requestParams)
        } catch (e) {
            logError "Error in handleTokenForDeviceDetail: ${e.message}"
        }
    } else {
        logError "Failed to get token for device detail: ${response.status}"
    }
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
        getDeviceDetailAsync(state.deviceId)
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
    logDebug "Stored setpointDelta in Celsius: ${state.setpointDeltaCelsius}"
    
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
    } catch (e) {
        logError "Error updating attributes: $e"
    }
}

/*****************************
 * Begin Thermostat Methods **
 ****************************/ 

void refresh() {
    updateThermostat()
    if(pollRate > 0)
        runIn(pollRate*60,"refresh")
}

void updateThermostat() {
    if (!state.deviceId) {
        logError "updateThermostat: Device not configured"
        return
    }
    getDeviceDetailAsync(state.deviceId)
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
    
    sendPutAsync("/v1/devices/${state.deviceId}/msp", [mode:modeNum, coolSetpoint:coolset, heatSetpoint:heatset])
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
    sendPutAsync("/v1/devices/${state.deviceId}/fan", [fanCirculate:0, fanCirculateSpeed:0])
    updateAttr("thermostatFanMode", "auto")
}

void fanCirculate() {
    sendPutAsync("/v1/devices/${state.deviceId}/fan", [fanCirculate:2, fanCirculateSpeed:0])
    updateAttr("thermostatFanMode", "circulate")
}

void fanOn() {
    sendPutAsync("/v1/devices/${state.deviceId}/fan", [fanCirculate:1, fanCirculateSpeed:0])
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
    
    sendPutAsync("/v1/devices/${state.deviceId}/msp", bodyMap)
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
    
    sendPutAsync("/v1/devices/${state.deviceId}/msp", bodyMap)
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
    state.integratorToken = token
    log.info "Credentials saved to state. Run initialize() to connect."
}

void enableSchedule() {
    logDebug "enableSchedule()"
    if (!state.deviceId) {
        logError "enableSchedule: Device not configured"
        return
    }
    
    logDebug "Enabling schedule for device ${state.deviceId}"
    sendSchedulePutAsync([scheduleEnabled: true])
}

void disableSchedule() {
    logDebug "disableSchedule()"
    if (!state.deviceId) {
        logError "disableSchedule: Device not configured"
        return
    }
    
    logDebug "Disabling schedule for device ${state.deviceId}"
    sendSchedulePutAsync([scheduleEnabled: false])
}

void sendSchedulePutAsync(Map bodyMap) {
    // Get fresh token before making request
    getAuthTokenAsyncThenSchedulePut(bodyMap)
}

void getAuthTokenAsyncThenSchedulePut(Map bodyMap) {
    logDebug "Getting fresh token for schedule PUT request"
    
    Map requestParams = [
        uri: "${serverPath}/v1/token",
        requestContentType: 'application/json',
        contentType: 'application/json',
        timeout: 30,
        headers: [
            'Content-Type': 'application/json',
            'x-api-key': "${state.daiApiKey}"
        ],
        body: JsonOutput.toJson([
            email: "${state.email}",
            integratorToken: "${state.integratorToken}"
        ])
    ]
    
    asynchttpPost('handleTokenForSchedulePut', requestParams, [bodyMap: bodyMap])
}

void handleTokenForSchedulePut(response, data) {
    if (response.status == 200) {
        try {
            def jsonData = response.json
            logDebug "Fresh token obtained for schedule PUT"
            
            Map bodyMap = data.bodyMap
            
            logDebug "Making schedule PUT with: ${bodyMap}"
            
            def bodyText = JsonOutput.toJson(bodyMap)
            logDebug "JSON body: ${bodyText}"
            
            // Use token inline in request
            Map requestParams = [
                uri: "${serverPath}/v1/devices/${state.deviceId}/schedule",
                requestContentType: 'application/json',
                contentType: 'application/json',
                timeout: 30,
                headers: [
                    'Accept': 'application/json',
                    'x-api-key': "${state.daiApiKey}",
                    'Authorization': "Bearer ${jsonData.accessToken}"
                ],
                body: bodyText
            ]
            
            asynchttpPut('handleSchedulePutResponse', requestParams)
        } catch (e) {
            logError "Error in handleTokenForSchedulePut: ${e.message}"
        }
    } else {
        logError "Failed to get token for schedule PUT: ${response.status}"
    }
}

void handleSchedulePutResponse(response, data) {
    if (response.status == 200) {
        logDebug "✓ Schedule PUT request successful"
        // Refresh to get updated state - API says 15 secs to stabilize state
        runIn(15, 'updateThermostat')
    } else {
        logError "Schedule PUT request failed: ${response.status}"
    }
}

/***************************
 * End Thermostat Methods **
 **************************/

void sendPutAsync(String command, Map bodyMap) {
    // Get fresh token before making request
    getAuthTokenAsyncThenPut(command, bodyMap)
}

void getAuthTokenAsyncThenPut(String command, Map bodyMap) {
    logDebug "Getting fresh token for PUT request"
    
    Map requestParams = [
        uri: "${serverPath}/v1/token",
        requestContentType: 'application/json',
        contentType: 'application/json',
        timeout: 30,
        headers: [
            'Content-Type': 'application/json',
            'x-api-key': "${state.daiApiKey}"
        ],
        body: JsonOutput.toJson([
            email: "${state.email}",
            integratorToken: "${state.integratorToken}"
        ])
    ]
    
    asynchttpPost('handleTokenForPut', requestParams, [command: command, bodyMap: bodyMap])
}

void handleTokenForPut(response, data) {
    if (response.status == 200) {
        try {
            def jsonData = response.json
            logDebug "Fresh token obtained for PUT"
            
            String command = data.command
            Map bodyMap = data.bodyMap
            
            logDebug "Making PUT to: ${command}"
            logDebug "Body map: ${bodyMap}"
            
            def bodyText = JsonOutput.toJson(bodyMap)
            logDebug "JSON body: ${bodyText}"
            
            // Use token inline in request
            Map requestParams = [
                uri: "${serverPath}${command}",
                requestContentType: 'application/json',
                contentType: 'application/json',
                timeout: 30,
                headers: [
                    'Accept': 'application/json',
                    'x-api-key': "${state.daiApiKey}",
                    'Authorization': "Bearer ${jsonData.accessToken}"
                ],
                body: bodyText
            ]
            
            logDebug "Request params: uri=${requestParams.uri}, headers=${requestParams.headers.keySet()}"
            
            asynchttpPut('handlePutResponse', requestParams)
        } catch (e) {
            logError "Error in handleTokenForPut: ${e.message}"
        }
    } else {
        logError "Failed to get token for PUT: ${response.status}"
    }
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
void logsOff() {
    device.updateSetting("debugEnabled",[value:"false",type:"bool"])
}

private boolean useFahrenheit() {
    return location.temperatureScale == "F"
}
