# Honeywell T6 Pro Thermostat (Lite) for Hubitat Elevation

A lightweight Z-Wave driver for the Honeywell T6 Pro (TH6320ZW) built for low hub load.

It was written to fix `LimitExceededException: Device generates excessive hub load` errors seen with the original driver.

## ✨ Features

- **Thermostat control**: modes (off / heat / cool / auto / emergency heat), heating and cooling setpoints, fan mode (auto / on / circulate)
- **Sensors**: temperature, humidity, battery, power source (mains / battery)
- **Operating state**: idle, heating, cooling, fan only, pending heat/cool
- **Thermostat settings**: sensor calibration (±3°) and idle display brightness
- **Clock sync**: sets the thermostat's clock daily at 3:15am, so its built-in schedule follows DST
- **°F / °C**: temperatures and setpoints are converted to the hub's temperature scale

## ⚡ What makes it "Lite"

| Original driver | This driver |
|-----------------|-------------|
| Extra Z-Wave request after every fan state report | None |
| Operating state request on nearly every BasicSet | Only when it disagrees with the current state; bursts are debounced |
| Database read before every event | None (Hubitat already drops unchanged values) |
| `state` write on every report and command | Only when the heat/cool direction changes |
| Clock sync every 3 hours + every refresh | Once a day |
| All 42 installer parameters re-sent on every save | Installer settings are left to the thermostat |

### Bugs fixed from the original
- Fan mode reports were never handled (version mismatch)
- The thermostat's clock was set one weekday off (Java counts from Sunday, Z-Wave from Monday)
- A malformed command class version (`043` is octal)

## 🚀 Installation

### Hubitat Package Manager (recommended)
1. **Apps** → **Hubitat Package Manager** → **Install** → **From a URL**
2. Enter: `https://raw.githubusercontent.com/timothydodd/hubitat-thermostat/master/packageManifest.json`

### Manual
1. Copy the code from `honeywell-t6-pro-driver.groovy`
2. In Hubitat: **Drivers Code** → **New Driver** → paste → **Save**

### Switching an existing thermostat to this driver
1. Open the thermostat's device page and set **Type** to "Honeywell T6 Pro Thermostat (Lite)" → **Save Device**
2. Click **Save Preferences**
3. Click **Configure**, then **Refresh**

## 🎮 Commands

| Command | What it does |
|---------|--------------|
| **Configure** | Adds the hub to the thermostat's lifeline so it reports changes on its own, publishes supported modes, schedules the daily clock sync, then refreshes. Safe to run any time. |
| **Refresh** | Reads mode, operating state, setpoints, fan mode, temperature, humidity, battery, brightness and calibration |
| **setHeatingSetpoint / setCoolingSetpoint** | Whole degrees in °F, half degrees in °C |
| **setThermostatMode** / off / heat / cool / auto / emergencyHeat | Sets the thermostat mode |
| **setThermostatFanMode** / fanOn / fanAuto / fanCirculate | Sets the fan mode |
| **setSensorCalibration** | Offsets the thermostat's temperature sensor by -3 to +3 degrees |
| **setIdleBrightness** | Idle display brightness, 0-5 |
| **syncClock** | Sets the thermostat's clock to the hub's time now |

## 📊 Attributes

Standard thermostat attributes (`thermostatMode`, `thermostatOperatingState`, `thermostatFanMode`, `heatingSetpoint`, `coolingSetpoint`, `thermostatSetpoint`, `temperature`, `humidity`, `battery`, `powerSource`), plus:

| Attribute | Description |
|-----------|-------------|
| `sensorCalibration` | Current sensor offset |
| `idleBrightness` | Current idle display brightness |

In **auto** mode, `thermostatSetpoint` follows whichever setpoint the equipment is working toward (or last worked toward when idle).

## 🔍 Troubleshooting

- **Device page doesn't update when changed at the thermostat**: click **Configure**. This re-adds the hub to the thermostat's lifeline.
- **Thermostat schedule runs on the wrong day or time**: click **syncClock** and check the hub's time zone under **Settings** → **Location**.
- **Installer settings** (equipment type, stages, cycle rates, filter reminders, etc.) are not exposed. Set them at the thermostat in its installer menu.

## 🤝 Credits

Based on the **Advanced Honeywell T6 Pro** driver by **Bryan Copeland** ([djdizzyd](https://github.com/djdizzyd/hubitat)).

Rewritten by **Tim Dodd** with a focus on hub load and correctness.

## 📄 License

Licensed under the Apache License, Version 2.0. See the LICENSE file for details.
