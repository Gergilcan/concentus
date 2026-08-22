# The Homebrew cask — the TEMPLATE the release workflow stamps, not the published file.
#
# On every stable tag, the "Update the Homebrew tap" step in release.yml takes this file, fills
# version and both sha256 lines from the release's dmgs, and commits the result to the tap
# repository (github.com/Gergilcan/homebrew-concentus — created once, by hand, public). The
# cask's shape is edited HERE; the tap only ever receives stamped copies.
#
# The placeholders below are load-bearing: the workflow replaces the `version` line and the two
# REPLACE_WITH_* tokens with sed. Renaming them means the tap silently publishes a cask pointing
# at nothing.
#
# Users: brew tap gergilcan/concentus && brew install --cask concentus
cask "concentus" do
  arch arm: "arm64", intel: "x64"

  version "0.0.0" # ← the first macOS release replaces this
  sha256 arm:   "REPLACE_WITH_ARM64_DMG_SHA256",
         intel: "REPLACE_WITH_X64_DMG_SHA256"

  url "https://github.com/Gergilcan/concentus/releases/download/v#{version}/Concentus-#{version}-#{arch}.dmg"
  name "Concentus"
  desc "Visual multi-agent AI orchestration that runs on your Claude subscription"
  homepage "https://github.com/Gergilcan/concentus"

  auto_updates true
  depends_on macos: ">= :monterey"

  app "Concentus.app"

  zap trash: [
    "~/Library/Application Support/Concentus",
    "~/Library/Preferences/com.concentus.desktop.plist",
    "~/Library/Saved Application State/com.concentus.desktop.savedState",
  ]
end
