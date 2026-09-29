<#
  install-apps.ps1 - Yalnizca Kutu uygulamalarini kurar ve Kutu Home'u ana ekran yapar.
  install.ps1'den farki: kutuyu hafifletmez (paket kapatma, animasyon ayari yok).

  Kullanim (Windows PowerShell penceresine yapistirin):
    irm https://github.com/coraspirin/android-tv-lite-launcher/releases/latest/download/install-apps.ps1 | iex

  Ne yapar:
    - ADB yoksa Google'in resmi platform-tools paketini indirir (%LOCALAPPDATA%\Kutu)
    - kutuya ag uzerinden baglanir
    - son GitHub Release'teki Kutu Mirror, Kutu Aktarim ve Kutu Home'u indirir,
      SHA-256'larini kutu-versions.json ile dogrular ve kurar
    - izinleri verir, Kutu Home'u ana ekran yapar
    - ekran koruyucu yoksa ya da kapaliysa Kutu Home'un saatini koruyucu yapar
    - Mi Box'ta gerekirse yalnizca stok ana ekrani kapatir (pm disable-user; geri alinabilir)
    - yapilan her degisikligi Belgeler\Kutu altina geri alma dosyasina yazar
    - en son kutuda ADB'yi kapatir

  Tekrar calistirilabilir: guncel olan her sey atlanir.

  Yerel deneme (hicbir sey degistirmez):
    & ([scriptblock]::Create((irm <adres>))) -DryRun -Ip 192.168.1.50

  Dil: Windows Turkce ise Turkce, degilse Ingilizce. -Lang tr|en ile zorlanabilir.
  Language: Turkish on a Turkish Windows, English otherwise; -Lang tr|en forces one.

  Bu dosyanin govdesi bilerek ASCII'dir: irm dosyayi hangi kodlamayla okursa okusun Turkce
  metinler bozulmasin diye kullaniciya gosterilen yazilar \uXXXX kacislariyla saklanir ve
  T fonksiyonunda cozulur.
#>
param(
    [string]$Ip,
    [switch]$DryRun,
    [switch]$KeepAdb,
    [switch]$NoPause,
    [ValidateSet('', 'tr', 'en')][string]$Lang = ''
)

function Invoke-KutuInstall {
    param([string]$Ip, [bool]$DryRun, [bool]$KeepAdb, [bool]$NoPause, [string]$Lang)

    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'   # Windows PowerShell 5.1'de indirmeyi cok yavaslatir
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

    $Repo = 'coraspirin/android-tv-lite-launcher'
    # Turkish on a Turkish Windows, English everywhere else
    $script:Turkish = if ($Lang) { $Lang -eq 'tr' } else { (Get-UICulture).TwoLetterISOLanguageName -eq 'tr' }
    $TotalSteps = 11
    $KutuHome = 'local.kutu.home/.HomeActivity'
    $KutuDream = 'local.kutu.home/.KutuDream'
    # yuklenme sirasi: Kutu Home en son, cunku o kurulunca ana ekran degisebilir
    $InstallOrder = @('local.kutu.mirror', 'local.kutu.transfer', 'local.kutu.home')
    $Names = @{
        'local.kutu.mirror'   = @('Kutu Yans\u0131tma', 'Kutu Mirror')
        'local.kutu.transfer' = @('Kutu Aktar\u0131m', 'Kutu Transfer')
        'local.kutu.home'     = @('Kutu Home', 'Kutu Home')
    }

    # ---------------------------------------------------------------- yardimcilar
    # every message is written twice, Turkish then English; L picks by the Windows language
    function T([string]$s) { [regex]::Unescape($s) }
    function L([string]$tr, [string]$en) { if ($script:Turkish) { T $tr } else { $en } }
    function Step([int]$n, [string]$tr, [string]$en) {
        Write-Host ''
        Write-Host ('[{0}/{1}] {2}' -f $n, $TotalSteps, (L $tr $en)) -ForegroundColor Cyan
    }
    function Info([string]$tr, [string]$en) { Write-Host ('  ' + ((L $tr $en) -f $args)) }
    function Good([string]$tr, [string]$en) { Write-Host ('  ' + ((L $tr $en) -f $args)) -ForegroundColor Green }
    function Warn([string]$tr, [string]$en) { Write-Host ('  ' + ((L $tr $en) -f $args)) -ForegroundColor Yellow }
    function Fail([string]$tr, [string]$en) { throw ('KUTU:' + ((L $tr $en) -f $args)) }
    function Nm([string]$pkg) { L $Names[$pkg][0] $Names[$pkg][1] }

    # adb'yi dogrudan Process ile calistirir: PS 5.1'de stderr yonlendirmesi hataya donusmesin
    function Run([string[]]$argv, [int]$timeoutSec = 120) {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $script:AdbExe
        $psi.Arguments = ($argv | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        $outTask = $p.StandardOutput.ReadToEndAsync()
        $errTask = $p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit($timeoutSec * 1000)) {
            try { $p.Kill() } catch { }
            return [pscustomobject]@{ Code = -1; Out = 'timeout' }
        }
        $p.WaitForExit()
        return [pscustomobject]@{ Code = $p.ExitCode; Out = ($outTask.Result + $errTask.Result).Trim() }
    }
    # pencere dogrudan acildiysa sonuc okunmadan kapanmasin
    function Pause {
        if ($NoPause) { return }
        try { Read-Host (L 'Kapatmak i\u00e7in Enter\u0027a bas\u0131n' 'Press Enter to close') | Out-Null } catch { }
    }
    function Sh([string]$cmd) { (Run @('-s', $script:Serial, 'shell', $cmd)).Out }
    # degistiren komutlar: DryRun'da yalnizca yazilir
    function Change([string]$what, [string]$cmd) {
        if ($DryRun) { Write-Host (('  [' + (L 'deneme' 'dry run') + '] ') + $cmd) -ForegroundColor DarkGray; return 'dry-run' }
        $r = Sh $cmd
        $script:Changes.Add([pscustomobject]@{ What = $what; Cmd = $cmd }) | Out-Null
        return $r
    }
    function HomeNow {
        $lines = @((Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -split "`r?`n" |
                Where-Object { $_.Trim() })
        if ($lines.Count -eq 0) { return '' }
        return $lines[-1].Trim()
    }
    function Pkgs([string]$flag) {
        $set = @{}
        foreach ($l in ((Sh "pm list packages $flag") -split "`r?`n")) {
            $l = $l.Trim()
            if ($l.StartsWith('package:')) { $set[$l.Substring(8)] = $true }
        }
        return $set
    }
    function InstalledCode([string]$pkg) {
        $o = Sh "dumpsys package $pkg | grep -m1 versionCode="
        if ($o -match 'versionCode=(\d+)') { return [long]$Matches[1] }
        return -1
    }
    # the box has the app signed with another key (a build from before the release keys), which
    # Android cannot update in place: after asking, uninstall it and install the new one.
    # The app's own folder (Kutu Aktarim's received files and backgrounds) is copied aside and put back.
    function Reinstall([string]$pkg, [string]$apk) {
        $name = Nm $pkg
        Warn '{0} kutuda farkl\u0131 bir imzayla kurulmu\u015f (eski bir derlemeden kalma); Android onu yerinde g\u00fcncelleyemez.' '{0} on the box is signed with a different key (left from an older build); Android cannot update it in place.' $name
        Warn 'Eskisi kald\u0131r\u0131l\u0131p yenisi kurulabilir; uygulaman\u0131n ayarlar\u0131 s\u0131f\u0131rlan\u0131r, klas\u00f6r\u00fcndeki dosyalar yedeklenip geri konur.' 'The old one can be uninstalled and the new one installed; the app''s settings are reset, the files in its folder are backed up and put back.'
        $answer = ''
        try { $answer = (Read-Host ('  ' + (L 'Eskisini kald\u0131r\u0131p yenisini kuray\u0131m m\u0131? [E/h]' 'Uninstall the old one and install the new one? [Y/n]'))).Trim() } catch { $answer = 'n' }
        if ($answer -match '^[hHnN]') {
            Fail ('{0} kurulamad\u0131: eski s\u00fcr\u00fcm kald\u0131r\u0131lmad\u0131. ' +
                'Onu TV\u0027den kald\u0131r\u0131p komutu yeniden \u00e7al\u0131\u015ft\u0131rabilirsiniz.') ('{0} was not installed: the old version was left in place. ' +
                'You can uninstall it on the TV and run the command again.') $name
        }
        $data = "/sdcard/Android/data/$pkg/files"
        $backup = "/sdcard/Kutu-yedek/$pkg"
        $saved = (Sh "[ -d $data ] && echo y") -eq 'y'
        if ($saved -and (Sh "rm -rf $backup && mkdir -p /sdcard/Kutu-yedek && cp -r $data $backup && echo ok") -ne 'ok') {
            Fail '{0} dosyalar\u0131 yedeklenemedi; hi\u00e7bir \u015fey kald\u0131r\u0131lmad\u0131.' 'The files of {0} could not be backed up; nothing was uninstalled.' $name
        }
        $u = Sh "pm uninstall $pkg"
        if ($u -notmatch 'Success') { Fail '{0} kald\u0131r\u0131lamad\u0131: {1}' '{0} could not be uninstalled: {1}' $name $u }
        Good 'Eski {0} kald\u0131r\u0131ld\u0131.' 'The old {0} was uninstalled.' $name
        $r = Run @('-s', $script:Serial, 'install', $apk) 300
        if ($saved) {
            if ($r.Out -match 'Success' -and (Sh "mkdir -p $data && cp -r $backup/. $data/ && rm -rf $backup && echo ok") -eq 'ok') {
                Good '{0} dosyalar\u0131 geri kondu.' 'The files of {0} were put back.' $name
            } else {
                Warn '{0} dosyalar\u0131n\u0131n yede\u011fi kutuda duruyor: {1}' 'The backup of the {0} files is still on the box: {1}' $name $backup
            }
        }
        return $r
    }
    function WaitBoot([int]$timeoutSec) {
        $deadline = (Get-Date).AddSeconds($timeoutSec)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 5
            Run @('connect', $script:Serial) 15 | Out-Null
            if ((Sh 'getprop sys.boot_completed') -eq '1') { return $true }
        }
        return $false
    }

    $script:Changes = New-Object System.Collections.ArrayList
    $script:Serial = $null
    $script:AdbExe = $null
    $stockHome = $null
    $model = ''

    try {
        # ------------------------------------------------------------ 1. hazirlik
        Step 1 'TV\u0027de yap\u0131lacaklar' 'On the TV'
        if ($DryRun) { Warn 'DENEME MODU: kutuda hi\u00e7bir \u015fey de\u011fi\u015ftirilmeyecek.' 'DRY RUN: nothing on the box will be changed.' }
        Info '1. Ayarlar > Cihaz Tercihleri > Hakk\u0131nda > "Yap\u0131" sat\u0131r\u0131na 7 kez bas\u0131n.' '1. Settings > Device Preferences > About: press "Build" 7 times.'
        Info '   "Art\u0131k bir geli\u015ftiricisiniz" yaz\u0131s\u0131 \u00e7\u0131kar.' '   It says "You are now a developer".'
        Info '2. Ayarlar > Cihaz Tercihleri > Geli\u015ftirici se\u00e7enekleri > "USB hata ay\u0131klama" A\u00c7IK.' '2. Settings > Device Preferences > Developer options: turn "USB debugging" ON.'
        Info '   (Varsa "A\u011f \u00fczerinden hata ay\u0131klama" da A\u00c7IK.)' '   (If there is "Network debugging", turn it ON too.)'
        Info '3. TV\u0027nin IP adresi: Ayarlar > A\u011f ve \u0130nternet > ba\u011fl\u0131 oldu\u011funuz a\u011f.' '3. The TV IP address: Settings > Network & Internet > your network.'
        Info '   Bu bilgisayar ile TV ayn\u0131 a\u011fda olmal\u0131.' '   This computer and the TV must be on the same network.'

        # ------------------------------------------------------------ 2. IP
        Step 2 'TV\u0027nin IP adresi' 'The TV IP address'
        while ($true) {
            if (-not $Ip) { $Ip = (Read-Host (L '  IP adresini yaz\u0131p Enter\u0027a bas\u0131n (\u00f6rnek 192.168.1.50)' '  Type the IP address and press Enter (e.g. 192.168.1.50)')).Trim() }
            if ($Ip -match '^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:(\d{1,5}))?$' -and
                (@(1, 2, 3, 4) | Where-Object { [int]$Matches[$_] -gt 255 }).Count -eq 0) {
                if (-not $Matches[6]) { $Ip = "$Ip`:5555" }
                break
            }
            Warn 'Bu bir IP adresi gibi g\u00f6r\u00fcnm\u00fcyor: {0}' 'That does not look like an IP address: {0}' $Ip
            $Ip = $null
        }
        $script:Serial = $Ip
        Good 'Hedef: {0}' 'Target: {0}' $Ip

        # ------------------------------------------------------------ 3. ADB
        Step 3 'ADB haz\u0131rlan\u0131yor' 'Preparing ADB'
        $ptDir = Join-Path $env:LOCALAPPDATA 'Kutu\platform-tools'
        $local = Join-Path $ptDir 'adb.exe'
        $onPath = Get-Command adb.exe -ErrorAction SilentlyContinue
        if (Test-Path $local) {
            $script:AdbExe = $local
        } elseif ($onPath) {
            $script:AdbExe = $onPath.Source
        } else {
            Info 'Google\u0027dan platform-tools indiriliyor...' 'Downloading platform-tools from Google...'
            $zip = Join-Path $env:TEMP 'kutu-platform-tools.zip'
            Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
            $parent = Split-Path $ptDir
            New-Item -ItemType Directory -Force $parent | Out-Null
            if (Test-Path $ptDir) { Remove-Item $ptDir -Recurse -Force }
            Expand-Archive $zip -DestinationPath $parent -Force
            Remove-Item $zip -Force
            if (-not (Test-Path $local)) { Fail 'ADB indirilemedi.' 'Could not download ADB.' }
            $script:AdbExe = $local
        }
        Run @('start-server') 30 | Out-Null
        Good 'ADB: {0}' 'ADB: {0}' $script:AdbExe

        # ------------------------------------------------------------ 4. baglan
        Step 4 'Kutuya ba\u011flan\u0131l\u0131yor' 'Connecting to the box'
        $deadline = (Get-Date).AddSeconds(120)
        $told = $false
        $state = ''
        while ((Get-Date) -lt $deadline) {
            $c = Run @('connect', $Ip) 20
            $state = (Run @('-s', $Ip, 'get-state') 10).Out
            if ($state -eq 'device') { break }
            if (($state -match 'unauthorized' -or $c.Out -match 'authenticate') -and -not $told) {
                Warn 'TV ekran\u0131nda "USB hata ay\u0131klamaya izin verilsin mi?" penceresi a\u00e7\u0131ld\u0131.' 'The TV is showing "Allow USB debugging?".'
                Warn '"Bu bilgisayardan her zaman izin ver" kutusunu i\u015faretleyip TAMAM\u0027a bas\u0131n. Bekliyorum...' 'Tick "Always allow from this computer" and press OK. Waiting...'
                $told = $true
            }
            Start-Sleep -Seconds 3
        }
        if ($state -ne 'device') {
            Fail ('Kutuya ba\u011flan\u0131lamad\u0131. Kontrol edin: IP do\u011fru mu, USB hata ay\u0131klama a\u00e7\u0131k m\u0131, ' +
                'bilgisayar ile TV ayn\u0131 a\u011fda m\u0131, TV\u0027deki izin penceresi onayland\u0131 m\u0131. Sonra komutu yeniden \u00e7al\u0131\u015ft\u0131r\u0131n.') ('Could not connect to the box. Check that the IP is right, USB debugging is on, ' +
                'this computer and the TV are on the same network and the prompt on the TV was accepted. Then run the command again.')
        }
        Good 'Ba\u011fland\u0131.' 'Connected.'

        # ------------------------------------------------------------ 5. cihaz
        Step 5 'Cihaz kontrol ediliyor' 'Checking the device'
        $sdk = 0; [int]::TryParse((Sh 'getprop ro.build.version.sdk'), [ref]$sdk) | Out-Null
        $abis = Sh 'getprop ro.product.cpu.abilist'
        $model = Sh 'getprop ro.product.model'
        $device = Sh 'getprop ro.product.device'
        $release = Sh 'getprop ro.build.version.release'
        Info 'Model: {0} ({1}), Android {2}' 'Model: {0} ({1}), Android {2}' $model $device $release
        if ($sdk -lt 28) { Fail 'Bu kutu Android 9 (API 28) veya \u00fcst\u00fc de\u011fil; Kutu uygulamalar\u0131 kurulamaz.' 'This box is older than Android 9 (API 28); the Kutu apps cannot be installed.' }
        if ($abis -notmatch 'armeabi-v7a') { Fail 'Bu kutunun i\u015flemcisi desteklenmiyor (armeabi-v7a yok: {0}).' 'This box has an unsupported processor (no armeabi-v7a: {0}).' $abis }
        Good 'Uygun.' 'Supported.'

        # ------------------------------------------------------------ 6. indir
        Step 6 'Son s\u00fcr\u00fcm GitHub\u0027dan indiriliyor' 'Downloading the latest release from GitHub'
        $hdr = @{ 'User-Agent' = 'kutu-install'; 'Accept' = 'application/vnd.github+json' }
        $rel = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" -Headers $hdr
        $assets = @{}
        foreach ($a in $rel.assets) { $assets[$a.name] = $a.browser_download_url }
        if (-not $assets['kutu-versions.json']) { Fail 'Son s\u00fcr\u00fcmde kutu-versions.json yok ({0}).' 'The latest release has no kutu-versions.json ({0}).' $rel.tag_name }
        $manifest = Invoke-RestMethod $assets['kutu-versions.json'] -Headers $hdr
        if ($manifest -is [string]) { $manifest = $manifest | ConvertFrom-Json }
        Info 'S\u00fcr\u00fcm: {0}' 'Release: {0}' $rel.tag_name
        $work = Join-Path $env:TEMP 'kutu-kurulum'
        New-Item -ItemType Directory -Force $work | Out-Null
        $apks = @{}
        foreach ($pkg in $InstallOrder) {
            $m = $manifest.apps.$pkg
            if (-not $m) { Fail '{0} son s\u00fcr\u00fcmde yok.' '{0} is not in the latest release.' $pkg }
            if (-not $assets[$m.asset]) { Fail '{0} dosyas\u0131 s\u00fcr\u00fcmde yok.' '{0} is missing from the release.' $m.asset }
            $file = Join-Path $work $m.asset
            Invoke-WebRequest $assets[$m.asset] -OutFile $file -Headers @{ 'User-Agent' = 'kutu-install' } -UseBasicParsing
            $sha = (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($sha -ne $m.sha256.ToLowerInvariant()) {
                Fail '{0} do\u011frulanamad\u0131 (SHA-256 uymuyor). Kurulum durduruldu; hi\u00e7bir \u015fey kurulmad\u0131.' '{0} failed verification (SHA-256 mismatch). Stopped; nothing was installed.' $m.asset
            }
            $apks[$pkg] = $file
            Good '{0} {1} indirildi, do\u011fruland\u0131.' '{0} {1} downloaded and verified.' (Nm $pkg) $m.versionName
        }

        # ------------------------------------------------------------ 7. kur
        Step 7 'Uygulamalar kuruluyor' 'Installing the apps'
        foreach ($pkg in $InstallOrder) {
            $m = $manifest.apps.$pkg
            $have = InstalledCode $pkg
            $name = Nm $pkg
            if ($have -ge [long]$m.versionCode) { Good '{0}: zaten g\u00fcncel.' '{0}: already up to date.' $name; continue }
            if ($DryRun) { Write-Host "  [$(L 'deneme' 'dry run')] adb install -r $($m.asset)" -ForegroundColor DarkGray; continue }
            Info '{0} {1} kuruluyor...' 'Installing {0} {1}...' $name $m.versionName
            $r = Run @('-s', $script:Serial, 'install', '-r', $apks[$pkg]) 300
            if ($r.Out -notmatch 'Success' -and $r.Out -match 'UPDATE_INCOMPATIBLE|INCONSISTENT_CERTIFICATES') {
                $r = Reinstall $pkg $apks[$pkg]
            }
            if ($r.Out -notmatch 'Success') {
                if ($r.Out -match 'NO_MATCHING_ABIS') {
                    Fail '{0} kurulamad\u0131: bu kutunun i\u015flemcisi desteklenmiyor.' '{0} could not be installed: this box has an unsupported processor.' $name
                }
                Fail '{0} kurulamad\u0131: {1}' '{0} could not be installed: {1}' $name $r.Out
            }
            $script:Changes.Add([pscustomobject]@{ What = "install $pkg"; Cmd = "uninstall $pkg" }) | Out-Null
            Good '{0} kuruldu.' '{0} installed.' $name
        }

        # ------------------------------------------------------------ 8. izinler
        Step 8 '\u0130zinler veriliyor' 'Granting permissions'
        foreach ($g in @(
                @('local.kutu.home', 'android.permission.ACCESS_COARSE_LOCATION'),
                @('local.kutu.transfer', 'android.permission.READ_EXTERNAL_STORAGE'),
                @('local.kutu.transfer', 'android.permission.WRITE_EXTERNAL_STORAGE'))) {
            if ($DryRun) { Write-Host "  [$(L 'deneme' 'dry run')] pm grant $($g[0]) $($g[1])" -ForegroundColor DarkGray; continue }
            Sh "pm grant $($g[0]) $($g[1])" | Out-Null
        }
        if ($DryRun) { Write-Host ('  [' + (L 'deneme' 'dry run') + '] appops set local.kutu.transfer REQUEST_INSTALL_PACKAGES allow') -ForegroundColor DarkGray }
        else { Sh 'appops set local.kutu.transfer REQUEST_INSTALL_PACKAGES allow' | Out-Null }
        Good 'Wi-Fi ad\u0131, dosya aktar\u0131m\u0131 ve GitHub g\u00fcncellemesi i\u00e7in izinler verildi.' 'Permissions granted for the Wi-Fi name, file transfer and GitHub updates.'

        # Ekran koruyucu kapali ya da yoksa Android koruyucu yerine kutuyu hemen uyutur
        # (CEC ile TV de kapanir). O durumda Kutu Home'un saati koruyucu yapilir; gecerli
        # bir koruyucu varsa dokunulmaz.
        $dreamNow = Sh 'settings get secure screensaver_components'
        $dreamPkg = if ($dreamNow -and $dreamNow -ne 'null') { $dreamNow.Split(',')[0].Split('/')[0] } else { '' }
        if ($dreamPkg -ne 'local.kutu.home' -and -not (Pkgs '-e')[$dreamPkg]) {
            Change "dream $dreamNow" "settings put secure screensaver_components $KutuDream" | Out-Null
            if ((Sh 'settings get secure screensaver_enabled') -ne '1') {
                Change 'dream-enabled' 'settings put secure screensaver_enabled 1' | Out-Null
            }
            Good 'Ekran koruyucu: Kutu Saat (kutu art\u0131k erken uyumaz).' 'Screen saver: Kutu Clock (the box no longer sleeps early).'
        }

        # ------------------------------------------------------------ 9. ana ekran
        Step 9 'Kutu Home ana ekran yap\u0131l\u0131yor' 'Making Kutu Home the home screen'
        $before = HomeNow
        if ($before -eq $KutuHome) {
            Good 'Kutu Home zaten ana ekran.' 'Kutu Home is already the home screen.'
        } else {
            if ($before -and $before -notmatch 'ResolverActivity' -and $before -notmatch '^local\.kutu\.') { $stockHome = $before }
            Change 'home' "cmd package set-home-activity $KutuHome" | Out-Null
            if (-not $DryRun -and (HomeNow) -ne $KutuHome) {
                # Mi Box'ta stok ana ekran priority=2 ile set-home-activity'yi ezer (rehber bolum 29)
                if (-not $stockHome) { Fail 'Ana ekran de\u011fi\u015ftirilemedi ve stok ana ekran bulunamad\u0131: {0}' 'Could not change the home screen and found no stock launcher: {0}' (HomeNow) }
                $stockPkg = $stockHome.Split('/')[0]
                Info 'Stok ana ekran ({0}) kapat\u0131l\u0131yor...' 'Disabling the stock launcher ({0})...' $stockPkg
                Change "disable $stockPkg" "pm disable-user --user 0 $stockPkg" | Out-Null
                Change 'home' "cmd package set-home-activity $KutuHome" | Out-Null
            }
            if ($DryRun) {
                Write-Host ('  [' + (L 'deneme' 'dry run') + '] reboot') -ForegroundColor DarkGray
            } else {
                Info 'Kutu yeniden ba\u015flat\u0131l\u0131yor (1-2 dakika s\u00fcrer)...' 'Restarting the box (takes 1-2 minutes)...'
                Run @('-s', $script:Serial, 'reboot') 30 | Out-Null
                Start-Sleep -Seconds 20
                if (-not (WaitBoot 240)) { Fail 'Kutu yeniden ba\u015flad\u0131ktan sonra ba\u011flan\u0131lamad\u0131. Kutu a\u00e7\u0131ld\u0131ysa komutu yeniden \u00e7al\u0131\u015ft\u0131r\u0131n.' 'Could not reconnect after the restart. Once the box is up, run the command again.' }
                Start-Sleep -Seconds 5
                $after = HomeNow
                if ($after -ne $KutuHome) {
                    if ($stockHome) {
                        $stockPkg = $stockHome.Split('/')[0]
                        Sh "pm enable --user 0 $stockPkg" | Out-Null
                        Sh "cmd package set-home-activity $stockHome" | Out-Null
                    }
                    Fail 'Kutu Home ana ekran olarak kalmad\u0131 ({0}); stok ana ekran geri getirildi.' 'Kutu Home did not stay the home screen ({0}); the stock launcher was restored.' $after
                }
                Good 'Kutu Home ana ekran.' 'Kutu Home is the home screen.'
            }
        }
    } catch {
        $msg = $_.Exception.Message
        Write-Host ''
        if ($msg.StartsWith('KUTU:')) {
            Write-Host ('  ' + $msg.Substring(5)) -ForegroundColor Red
        } else {
            Write-Host ('  ' + (L 'Beklenmeyen hata: ' 'Unexpected error: ') + $msg) -ForegroundColor Red
            Write-Host ('  ' + (L 'Komutu yeniden \u00e7al\u0131\u015ft\u0131rmay\u0131 deneyin; kurulu olanlar atlan\u0131r.' 'Try running the command again; anything already installed is skipped.')) -ForegroundColor Red
        }
        $failed = $true
    }

    # ---------------------------------------------------------------- 10. geri alma kaydi
    if ($script:Changes.Count -gt 0) {
        Step 10 'Geri alma kayd\u0131' 'Undo record'
        $dir = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Kutu'
        New-Item -ItemType Directory -Force $dir | Out-Null
        $file = Join-Path $dir ((L '{0}-{1}-geri-al.txt' '{0}-{1}-undo.txt') -f ($model -replace '[^\w-]', '_'), (Get-Date -Format 'yyyy-MM-dd_HHmm'))
        $lines = New-Object System.Collections.ArrayList
        $lines.Add((L 'Kutu kurulumu - yap\u0131lan de\u011fi\u015fiklikler ve geri alma' 'Kutu setup - changes made and how to undo them')) | Out-Null
        $lines.Add("Kutu: $model  ($($script:Serial))  $(Get-Date -Format 'yyyy-MM-dd HH:mm')") | Out-Null
        $lines.Add('') | Out-Null
        $lines.Add((L 'Geri almak i\u00e7in \u00f6nce kutuda ADB\u0027yi yeniden a\u00e7\u0131n:' 'To undo, first turn ADB back on on the box:')) | Out-Null
        $lines.Add((L '  Kutu Home > "Ayarlar" \u00e7ipi > Cihaz Tercihleri > Geli\u015ftirici se\u00e7enekleri > USB hata ay\u0131klama A\u00c7IK' '  Kutu Home > "Settings" chip > Device Preferences > Developer options > USB debugging ON')) | Out-Null
        $lines.Add((L 'Sonra bu bilgisayarda (adb yolu: ' 'Then on this computer (adb path: ') + $script:AdbExe + '):') | Out-Null
        $lines.Add("  adb connect $($script:Serial)") | Out-Null
        if ($stockHome) {
            $lines.Add((L '  # stok ana ekran\u0131 geri getir (ilk bu)' '  # bring back the stock launcher (do this first)')) | Out-Null
            $lines.Add("  adb shell pm enable --user 0 $($stockHome.Split('/')[0])") | Out-Null
            $lines.Add("  adb shell cmd package set-home-activity $stockHome") | Out-Null
        }
        foreach ($c in $script:Changes) {
            if ($c.What -like 'disable *') { $lines.Add("  adb shell pm enable --user 0 $($c.What.Substring(8))") | Out-Null }
            elseif ($c.What -like 'dream *') {
                $old = $c.What.Substring(6).Trim()
                if ($old -and $old -ne 'null') { $lines.Add("  adb shell settings put secure screensaver_components $old") | Out-Null }
                else { $lines.Add('  adb shell settings delete secure screensaver_components') | Out-Null }
            }
            elseif ($c.What -eq 'dream-enabled') { $lines.Add('  adb shell settings put secure screensaver_enabled 0') | Out-Null }
        }
        $lines.Add('') | Out-Null
        $lines.Add((L 'Hepsini tek seferde geri almak i\u00e7in depodaki RESTORE-ALL.ps1 de kullan\u0131labilir:' 'RESTORE-ALL.ps1 in the repository undoes all of it at once:')) | Out-Null
        $lines.Add("  https://github.com/$Repo/blob/main/RESTORE-ALL.ps1") | Out-Null
        $lines.Add('') | Out-Null
        $lines.Add((L 'Yap\u0131lan komutlar:' 'Commands that were run:')) | Out-Null
        foreach ($c in $script:Changes) { $lines.Add("  $($c.Cmd)") | Out-Null }
        [System.IO.File]::WriteAllLines($file, [string[]]$lines, (New-Object System.Text.UTF8Encoding $true))
        Good 'Kaydedildi: {0}' 'Saved: {0}' $file
    }

    if ($failed) {
        Write-Host ''
        Pause
        return
    }

    # ---------------------------------------------------------------- 11. bitis
    Step 11 'Bitti' 'Done'
    foreach ($pkg in $InstallOrder) { Good '{0} {1}' '{0} {1}' (Nm $pkg) $manifest.apps.$pkg.versionName }
    Info 'Kutu hafifletilmedi; tam kurulum i\u00e7in install.ps1 kullan\u0131labilir.' 'The box was not slimmed down; install.ps1 does the full setup.'
    Info 'Sonraki g\u00fcncellemeler i\u00e7in bilgisayar gerekmez:' 'Later updates do not need a computer:'
    Info 'Kutu Home > sol \u00fcstteki \u00e7ark > G\u00fcncellemeleri denetle.' 'Kutu Home > gear at the top left > Check for updates.'
    if ($DryRun) {
        Warn 'DENEME MODU: hi\u00e7bir \u015fey de\u011fi\u015ftirilmedi, ADB a\u00e7\u0131k b\u0131rak\u0131ld\u0131.' 'DRY RUN: nothing was changed, ADB left on.'
    } elseif ($KeepAdb) {
        Warn 'ADB a\u00e7\u0131k b\u0131rak\u0131ld\u0131 (-KeepAdb).' 'ADB left on (-KeepAdb).'
    } else {
        Info 'G\u00fcvenlik i\u00e7in kutuda ADB kapat\u0131l\u0131yor; ba\u011flant\u0131n\u0131n kopmas\u0131 normal.' 'Turning ADB off on the box for safety; the connection dropping is expected.'
        Sh 'settings put global adb_enabled 0' | Out-Null
        Run @('disconnect', $script:Serial) 10 | Out-Null
        Good 'ADB kapat\u0131ld\u0131.' 'ADB turned off.'
    }
    Write-Host ''
    Pause
}

Invoke-KutuInstall -Ip $Ip -DryRun:$DryRun -KeepAdb:$KeepAdb -NoPause:$NoPause -Lang $Lang
