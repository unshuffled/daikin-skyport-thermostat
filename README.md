# Daikin SkyPort Thermostat Hubitat Driver

Hubitat driver for Daikin One / Amana / Goodman smart thermostats using the Daikin One Open API (Integrator Cloud API).

## Features

- Controls Daikin SkyPort thermostats via the official Daikin One Open API.
- Exposes outdoor temperature as a separate child device (Generic Component Temperature Sensor) for use in automation rules (e.g., load shifting).
- Caches API access tokens and reuses them until expiry to reduce cloud calls.

## Supported Devices

Based on Daikin’s published API and limited testing, this driver is intended for:

- Daikin One+
- Daikin One Touch
- Amana Smart Thermostat
- Goodman GTST (expected to work, but currently untested)

You must have your thermostat registered and working in the official Daikin / Amana app first.

## Requirements

- Hubitat Elevation hub (C‑5 or later recommended).
- Access to the Daikin One Open API as an **Integrator**, including:
  - API key
  - Integrator email
  - Integrator token

See Daikin’s API documentation and terms for how to obtain these credentials and what you are allowed to do with them.

## Installation

1. **Obtain an Integrator Token and API Key**
   - Follow the Daikin API directions (https://www.daikinone.com/openapi/documentation/index.html)
   - to acquire an Integrator Token
   - and acquire a developer API Key
   
2. **Add the driver code**

   - In Hubitat, go to **Drivers Code → New Driver**.
   - Paste the contents of `skyport-thermostat.groovy`.
   - Click **Save**.

3. **Create a device**

   - Go to **Devices → Add Device → Add Virtual Device**.
   - Give it a name (e.g., `Daikin Thermostat`).
   - Set **Type** to `Daikin SkyPort Thermostat` (this driver).
   - Click **Save Device**.

4. **Save credentials**

   - Open the device page you just created.
   - In the **Commands** section, use `saveCredentials` and provide:
     - `apiKey` – your Daikin integrator API key.
     - `email` – the email address registered with the Daikin integrator portal.
     - `integratorToken` – the long token obtained from Daikin’s API process.
   - Click **Save Credentials** (Run Command).

5. **Initialize**

   - On the same device page, click the **Initialize** command.
   - The driver will:
     - Fetch an access token from the Daikin API.
     - Retrieve your available thermostats.
     - Select one based on the configured Thermostat Name (or the first available).
     - Fetch device details and populate attributes.
     - Create a child device for outdoor temperature.

5. **Configure preferences**

   - Click **Preferences** on the device page.
   - Set:
     - **Thermostat Name** – Select which of the discovered thermostats to use for this device, or leave blank to use the first thermostat.
     - **Thermostat Polling Rate** – in minutes (0 to disable polling).
     - **Enable debug logging** – optional, useful while testing (resets after 30 minutes)
   - Click **Save Preferences**

## Updating the Driver

For your convenience the driver includes an update URL to allow easy updates from the github repository.

1. Select the driver by name from the Hubitat "Drivers code" screen.

2. Click to open the "three dots" menu in the top right of the page.

3. Select "Import"

4. The import URL will automatically populate. Click the "Import" button and agree to overrided the old code.

5. Click the green "Save" button to apply the changes.

## Limitations and Caveats

- **Single thermostat per device**: This driver currently selects one thermostat from your Daikin account. If you have multiple thermostats, you may need one hub device instance per thermostat, each configured with an appropriate Thermostat Name.
- **Cloud dependency**: All communication goes through the Daikin cloud API. If Daikin’s service is down, slow, or your integrator access is revoked, the driver will not be able to control or read the thermostat.
- **API changes**: Daikin may change the Open API at any time. This driver targets the documented Integrator API as of its development date; future changes on Daikin’s side may require driver updates.
- **Scheduling behavior**: The driver exposes simple `enableSchedule()` / `disableSchedule()` commands that toggle the thermostat’s built-in schedule via the `/schedule` endpoint. It does not manage or edit the schedule contents.
- **Fan circulation**: Fan circulation settings supported for unitary systems only. This is a hardware limitation- mini split or VRV systems will ignore all fan settings. Enable the "Supports scheduled fan circulation" setting in the driver to indicate that you have a unitary system supporting fan setting changes.

## Developer Docs

### Parent thermostat device

The main device exposes standard thermostat capabilities:

- `Thermostat`, `ThermostatMode`, `ThermostatOperatingState`
- `ThermostatHeatingSetpoint`, `ThermostatCoolingSetpoint`
- `ThermostatFanMode`
- `Refresh`, `Configuration`, `Initialize`

Key commands:

- `setThermostatMode("heat" | "cool" | "auto" | "off" | "emergency heat")`
- `setHeatingSetpoint(temp)`
- `setCoolingSetpoint(temp)`
- `setThermostatFanMode("auto" | "on" | "circulate")`
- `enableSchedule()` / `disableSchedule()`
- `refresh()` – triggers an immediate poll of the thermostat.

The driver automatically enforces the minimum allowed difference between heating and cooling setpoints using the `setpointDelta` value from the API.

### Outdoor temperature child device

On first initialization, the driver creates one child device:

- **Type**: `Generic Component Temperature Sensor`
- **Name**: `<Your Thermostat Name> Outdoor Temp`
- **Attribute**: `temperature` (in the same unit as your Hubitat location, °F or °C)

This child device is updated each time the thermostat detail is refreshed, and can be used like any other temperature sensor in:

- Rule Machine
- Simple Automation Rules
- Your own custom apps

Refreshing the child device calls `componentRefresh` on the parent, which in turn triggers a full thermostat refresh.

## Logging and Debugging

- Enable debug logging in Preferences while setting up or troubleshooting.
- The driver logs:
  - Initialization steps and API call boundaries (auth, device list, device detail).
  - Token caching events (when a new token is cached and when it is considered expired).
  - Errors when API calls fail or responses can’t be parsed.

Debug logging automatically resets after a timeout (30 mins) to reduce log volume.

## License and Credits

This driver is licensed under the **Apache License, Version 2.0**.

Licensed under Apache 2.0 License. See `LICENSE` file for full license text and terms.
