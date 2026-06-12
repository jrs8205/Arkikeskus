# Yhdistaa Pixel 8a:n langattomalla adb:lla. Portti vaihtuu joka kerta -> haetaan mDNS:lla.
# Kaytto:  powershell -File tools\connect-phone.ps1
# Paritus (kerran / jos katkennut): puhelimessa Kehittajaasetukset -> Langaton virheenkorjaus
# -> Muodosta laitepari koodilla, sitten:  adb pair <ip:port> <koodi>
# (HUOM: pair nayttaa "protocol fault" -virheen VAIKKA paritus onnistuu.)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

$found = $null
foreach ($i in 1..5) {
    $out = & $adb mdns services 2>$null
    $line = $out | Select-String '_adb-tls-connect' | Select-Object -First 1
    if ($line -and $line.Line -match '(\d+\.\d+\.\d+\.\d+:\d+)') {
        $found = $Matches[1]
        break
    }
    Start-Sleep -Seconds 2
}

if ($found) {
    Write-Host "Yhdistetaan: $found"
    & $adb connect $found
} else {
    Write-Host "Puhelinta ei loytynyt verkosta."
    Write-Host "Tarkista: sama WiFi + Langaton virheenkorjaus paalla puhelimessa."
}
& $adb devices
