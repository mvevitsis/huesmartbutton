/**
 *  Hue Smart Button
 *
 *  Copyright 2021 Matvei Vevitsis
 *  Based on code copyright SmartThings
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
 */

import groovy.json.JsonOutput
import physicalgraph.zigbee.zcl.DataType

metadata {
    definition (
    name: "Hue Smart Button", 
    namespace: "circlefield05082", 
    author: "Matvei Vevitsis", 
    //mnmn: "SmartThingsCommunity", 
	vid: "SmartThings-smartthings-SmartSense_Button", 
    //vid: "03f89b0d-2bd5-313d-a096-78981c1cbaa2"
	ocfDeviceType: "x.com.st.d.remotecontroller"
    ) 
    {
        
       	capability "Actuator"
        capability "Battery"
        capability "Button"
        capability "Holdable Button"        
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "Health Check"
        

        fingerprint profileId: "0104", inClusters: "0000, 0001, 0003, FC00, 1000", outClusters: "0019, 0000, 0003, 0004, 0006, 0008, 0005, 1000", manufacturer: "Philips", model: "ROM001", deviceJoinName: "Hue Smart Button" 
    }
        
}


def parse(String description) {
    def event = zigbee.getEvent(description)
    def descLog = zigbee.parseDescriptionAsMap(description)
    log.debug "Parsed Description : $descLog"
    if (event) {
        sendEvent(event)
    }
    else {
        if ((description?.startsWith("catchall:")) || (description?.startsWith("read attr -"))) {
            def descMap = zigbee.parseDescriptionAsMap(description)
            if (descMap.clusterInt == 0x0001 && descMap.attrInt == 0x0020 && descMap.value != null) {
                event = getBatteryResult(zigbee.convertHexToInt(descMap.value))
            }
            else if (descMap.clusterInt == 0x0006 || descMap.clusterInt == 0x0008) {
                event = parseNonIasButtonMessage(descMap)
            }
        }
        
        log.debug "Parse returned $event"
        def result = event ? createEvent(event) : []

        if (description?.startsWith('enroll request')) {
            List cmds = zigbee.enrollResponse()
            result = cmds?.collect { new physicalgraph.device.HubAction(it) }
        }
        return result
    }
}

private Map getBatteryResult(rawValue) {
    def volts = rawValue / 10
    log.debug "Battery voltage rawValue = ${rawValue} -> ${volts}v"
	if (volts > 3.0 || volts == 0 || rawValue == 0xFF) {
        return [:]
    }
    else {
        def result = [
                name: 'battery'
        ]
        def minVolts = 2.1
        def maxVolts = 3.0
        def pct = (volts - minVolts) / (maxVolts - minVolts)
        result.value = Math.min(100, (int)(pct * 100))
        def linkText = getLinkText(device)
        result.descriptionText = "${linkText} battery was ${result.value}%"
        return result
    }
}

private Map parseNonIasButtonMessage(Map descMap){
    def buttonState = ""
    def buttonNumber = 1
    if (descMap.clusterInt == 0x0006) {
        if (descMap.command == "01") {
            buttonState = "pushed"
		}
        else if (descMap.command == "40") {
            buttonState = "pushed"
       }
    }
    else if (descMap.clusterInt == 0x0008) {
        if (descMap.command == "02") {
        	if (state.holdCounter == 0){
			buttonState = "held"
            } else {
            log.debug "Blocking additional holds..."
            }
            state.holdCounter = state.holdCounter + 1
            log.debug "Hold count is $state.holdCounter"
		} else if (descMap.command == "03") {
        	buttonState = "released"
            state.holdCounter = 0
            log.debug "Hold count has been reset to $state.holdCounter"
		}
    } 
    if (buttonState){
   	def descriptionText = "$device.displayName button $buttonNumber was $buttonState"
    return createEvent(name: "button", value: buttonState, data: [buttonNumber: buttonNumber], descriptionText: descriptionText, isStateChange: true)
    } else {
    	return [:]
    }
}


def refresh() {
    log.debug "Refreshing Battery"
	return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20) +
            zigbee.enrollResponse()
}

def configure() {
    log.debug "Configuring Reporting, IAS CIE, and Bindings."
    return zigbee.onOffConfig() +
            zigbee.levelConfig() +
            zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20, DataType.UINT8, 30, 21600, 0x01) +
            zigbee.enrollResponse() +
            zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20) 
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    log.debug "Initializing holdCounter..."
    state.holdCounter = 0 
    def numberOfButtons = 1
    sendEvent(name: "supportedButtonValues", value: ["pushed","held"].encodeAsJson(), displayed: false)
	sendEvent(name: "numberOfButtons", value: numberOfButtons, displayed: false)
	numberOfButtons.times {
		sendEvent(name: "button", value: "pushed", data: [buttonNumber: it+1], displayed: false)
	}
	// These devices don't report regularly so they should only go OFFLINE when Hub is OFFLINE
	sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)
}

