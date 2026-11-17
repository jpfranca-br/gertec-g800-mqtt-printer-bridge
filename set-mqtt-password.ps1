# Asks for the MQTT "pos" password (hidden input) and stores it in secrets.properties.
# The build reads that file and embeds the password in the APK.
$file = Join-Path $PSScriptRoot 'secrets.properties'
$secure = Read-Host -AsSecureString 'MQTT password for user pos'
$plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if ([string]::IsNullOrEmpty($plain)) { Write-Host 'Empty password, nothing changed.'; exit 1 }
# Java .properties escaping for backslashes
$escaped = $plain.Replace('\', '\\')
$lines = Get-Content $file | ForEach-Object { if ($_ -match '^mqtt\.password=') { "mqtt.password=$escaped" } else { $_ } }
Set-Content -Path $file -Value $lines -Encoding ascii
Write-Host "Saved to $file"
