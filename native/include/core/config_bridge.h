#pragma once

#include <map>
#include <memory>
#include <string>

/**
 * @file config_bridge.h
 * @brief Defines a native-side cache for configuration data, such as the
 * obfuscation map.
 */

namespace vector::native {

/**
 * @class ConfigBridge
 * @brief A singleton that holds configuration data.
 */
class ConfigBridge {
public:
  virtual ~ConfigBridge() = default;

  /**
   * @brief Gets the singleton instance of the ConfigBridge.
   */
  static ConfigBridge *GetInstance() { return instance_.get(); }

  /**
   * @brief Releases ownership of the singleton instance.
   */
  static std::unique_ptr<ConfigBridge> ReleaseInstance() {
    return std::move(instance_);
  }

  /// Gets a reference to the obfuscation map.
  virtual std::map<std::string, std::string> &obfuscation_map() = 0;

  /// Sets the obfuscation map.
  virtual void obfuscation_map(std::map<std::string, std::string> map) = 0;

protected:
  /// The singleton instance, managed alongside the main Context.
  static std::unique_ptr<ConfigBridge> instance_;
};

} // namespace vector::native
