# The Homebrew cask, written ahead of the build it needs.
#
# Casks are macOS-only, and there is no macOS build yet — shipping one means an Apple Developer
# account and notarization, because Gatekeeper blocks unsigned apps outright (see
# electron-builder.yml, where the target is deliberately absent). This file exists so that the day
# the notarized build lands, publishing to brew is a version bump and a repo push, not a research
# project.
#
# To activate, once mac artifacts exist in a release:
#   1. Create the tap repository: github.com/Gergilcan/homebrew-concentus (public, empty).
#   2. Copy this file into it under Casks/concentus.rb, with version and sha256 filled from the
#      release (shasum -a 256 Concentus-<version>-arm64.dmg).
#   3. Users then run:  brew tap gergilcan/concentus && brew install --cask concentus
#
# Nothing here is guessed: the artifact names match what electron-builder produces for a dmg
# target with the artifactName conventions already used on Windows and Linux.
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
