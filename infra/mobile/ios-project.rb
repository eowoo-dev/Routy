require "xcodeproj"

root = File.expand_path("../..", __dir__)
project = Xcodeproj::Project.open(File.join(root, "apps/web/ios/App/App.xcodeproj"))
app = project.targets.find { |target| target.name == "App" }
raise "App target is missing" unless app
number = ENV.fetch("RELEASE_BUILD_NUMBER")
raise "Invalid build number" unless number.match?(/\A[1-9]\d*\z/)
app.build_configurations.each do |config|
  next unless config.name == "Release"
  config.build_settings["CODE_SIGN_STYLE"] = "Manual"
  config.build_settings["CODE_SIGN_IDENTITY"] = "Apple Distribution"
  config.build_settings["DEVELOPMENT_TEAM"] = ENV.fetch("APPLE_TEAM_ID")
  config.build_settings["PROVISIONING_PROFILE_SPECIFIER"] = ENV.fetch("IOS_PROFILE_UUID")
  config.build_settings["CURRENT_PROJECT_VERSION"] = number
end
project.save
