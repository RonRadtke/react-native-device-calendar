#!/bin/bash
# Gives the example app full access to the calendars of a simulator, for the EventKit tests: ./grant-calendar-access.sh <udid>
#
# `simctl privacy grant calendar` writes an access of the kind before iOS 17, which EventKit 17 and later read as not yet asked.
# The script writes the access as a person grants it, with the simulator shut down so that the system reads it at the next boot.
set -euo pipefail

device="$1"
bundle="ronradtke.reactnativedevicecalendar.example"
database="$HOME/Library/Developer/CoreSimulator/Devices/$device/data/Library/TCC/TCC.db"

xcrun simctl boot "$device" 2>/dev/null || true
xcrun simctl bootstatus "$device" -b > /dev/null
xcrun simctl privacy "$device" grant calendar "$bundle"
xcrun simctl shutdown "$device"
sqlite3 "$database" "UPDATE access SET auth_reason = 2, auth_version = 2 WHERE service = 'kTCCServiceCalendar' AND client = '$bundle'"
xcrun simctl boot "$device"
xcrun simctl bootstatus "$device" -b > /dev/null
