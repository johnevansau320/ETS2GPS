# Builds the single-file Ets2LaneOverlay.exe (self-contained: no .NET install needed on the target PC). Needs the .NET 8 SDK.
dotnet publish "$PSScriptRoot\Ets2LaneOverlay.csproj" -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o "$PSScriptRoot\publish"
Write-Host "Built: $PSScriptRoot\publish\Ets2LaneOverlay.exe"
