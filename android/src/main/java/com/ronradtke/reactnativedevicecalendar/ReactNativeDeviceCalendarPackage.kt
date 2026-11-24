package com.ronradtke.reactnativedevicecalendar

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class ReactNativeDeviceCalendarPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    return if (name == ReactNativeDeviceCalendarModule.NAME) {
      ReactNativeDeviceCalendarModule(reactContext)
    } else {
      null
    }
  }

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      ReactNativeDeviceCalendarModule.NAME to ReactModuleInfo(
        name = ReactNativeDeviceCalendarModule.NAME,
        className = ReactNativeDeviceCalendarModule.NAME,
        canOverrideExistingModule = false,
        needsEagerInit = false,
        isCxxModule = false,
        isTurboModule = true
      )
    )
  }
}
