package com.ronradtke.reactnativedevicecalendar

import com.facebook.react.bridge.ReactApplicationContext

class ReactNativeDeviceCalendarModule(reactContext: ReactApplicationContext) :
  NativeReactNativeDeviceCalendarSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeReactNativeDeviceCalendarSpec.NAME
  }
}
