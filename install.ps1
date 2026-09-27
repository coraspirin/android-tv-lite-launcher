<#
  install.ps1 - Kutu'yu bir Android TV kutusuna tek komutla kurar.

  Kullanim (Windows PowerShell penceresine yapistirin):
    irm https://github.com/coraspirin/android-tv-lite-launcher/releases/latest/download/install.ps1 | iex

  Ne yapar:
    - ADB yoksa Google'in resmi platform-tools paketini indirir (%LOCALAPPDATA%\Kutu)
    - kutuya ag uzerinden baglanir
    - son GitHub Release'teki Kutu Mirror, Kutu Aktarim ve Kutu Home'u indirir,
      SHA-256'larini kutu-versions.json ile dogrular ve kurar
    - izinleri verir, Kutu Home'u ana ekran yapar
    - Mi Box S'te reklam/telemetri paketlerini kapatir (yalnizca pm disable-user; geri alinabilir)
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
    $TotalSteps = 12
    $KutuHome = 'local.kutu.home/.HomeActivity'
    # yuklenme sirasi: Kutu Home en son, cunku o kurulunca ana ekran degisebilir
    $InstallOrder = @('local.kutu.mirror', 'local.kutu.transfer', 'local.kutu.home')
    $Names = @{
        'local.kutu.mirror'   = @('Kutu Yans\u0131tma', 'Kutu Mirror')
        'local.kutu.transfer' = @('Kutu Aktar\u0131m', 'Kutu Transfer')
        'local.kutu.home'     = @('Kutu Home', 'Kutu Home')
    }
    # Mi Box S (MIBOX4) icin dogrulanmis liste, PROJECT-PACKAGES.txt'in proje kismi.
    # Cikarilanlar: ses yigini (katniss, pumpkin, tts - rehber kural 11, yalnizca kisi isterse)
    # ve stok ana ekran (tvlauncher - yalnizca gerekirse, adim 10'da).
    $Debloat = @(
        'com.miui.tv.analytics', 'tv.alphonso.alphonso_eula', 'com.google.android.tv.bugreportsender',
        'com.google.android.feedback', 'com.google.android.partnersetup',
        'com.xiaomi.android.tvsetup.partnercustomizer', 'android.autoinstalls.config.xioami.mibox3',
        'com.google.android.onetimeinitializer', 'com.android.onetimeinitializer',
        'com.google.android.tungsten.setupwraith',
        'com.android.printspooler', 'com.android.dreams.basic', 'com.google.android.backdrop',
        'com.google.android.marvin.talkback', 'com.google.android.syncadapters.calendar',
        'com.google.android.syncadapters.contacts', 'com.android.providers.calendar',
        'com.android.wallpaperbackup', 'com.android.backupconfirm', 'com.google.android.backuptransport',
        'com.android.sharedstoragebackup', 'com.android.providers.userdictionary',
        'com.android.companiondevicemanager', 'com.android.statementservice',
        'com.android.settings.intelligence', 'com.google.android.sss.authbridge',
        'com.mitv.tvhome.atv', 'com.mitv.tvhome.michannel', 'com.xm.webcontent',
        'com.mitv.download.service', 'com.mitv.videoplayer', 'com.xiaomi.mitv.updateservice',
        'com.google.android.tv', 'com.google.android.tvrecommendations'
    )
    $DebloatDevice = 'MIBOX4'

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
        $isMiBox = ($model -eq $DebloatDevice -or $device -eq $DebloatDevice)
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
            if ($r.Out -notmatch 'Success') {
                if ($r.Out -match 'UPDATE_INCOMPATIBLE|NO_MATCHING_ABIS|INCONSISTENT_CERTIFICATES') {
                    Fail ('{0} kurulamad\u0131: kutuda farkl\u0131 bir imzayla kurulmu\u015f eski bir {0} var. ' +
                        'Onu TV\u0027den kald\u0131r\u0131p komutu yeniden \u00e7al\u0131\u015ft\u0131r\u0131n.') ('{0} could not be installed: the box has an older {0} signed with a different key. ' +
                        'Uninstall it on the TV and run the command again.') $name
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

        # ------------------------------------------------------------ 9. hafifletme
        Step 9 'Kutu hafifletiliyor' 'Slimming down the box'
        if (-not $isMiBox) {
            Warn 'Bu kutu Mi Box S de\u011fil; paket listesi yaln\u0131zca Mi Box S i\u00e7in do\u011fruland\u0131\u011f\u0131ndan hafifletme atland\u0131.' 'This box is not a Mi Box S; the package list was only checked on a Mi Box S, so this step was skipped.'
        } else {
            $all = Pkgs ''
            $enabled = Pkgs '-e'
            $done = 0; $skipped = 0
            foreach ($p in $Debloat) {
                if (-not $all[$p] -or -not $enabled[$p]) { $skipped++; continue }
                $r = Change "disable $p" "pm disable-user --user 0 $p"
                if ($DryRun -or $r -match 'disabled-user') { $done++ } else { Warn '{0}: {1}' '{0}: {1}' $p $r }
            }
            Good '{0} paket kapat\u0131ld\u0131, {1} paket zaten kapal\u0131yd\u0131 ya da yoktu.' '{0} packages disabled, {1} were already disabled or not present.' $done $skipped
            if ((Sh 'settings get global window_animation_scale') -ne '0.5') {
                foreach ($k in @('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')) {
                    Change "animation $k" "settings put global $k 0.5" | Out-Null
                }
                Good 'Animasyonlar h\u0131zland\u0131r\u0131ld\u0131 (0.5).' 'Animations sped up (0.5).'
            }
        }

        # ------------------------------------------------------------ 10. ana ekran
        Step 10 'Kutu Home ana ekran yap\u0131l\u0131yor' 'Making Kutu Home the home screen'
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

    # ---------------------------------------------------------------- 11. geri alma kaydi
    if ($script:Changes.Count -gt 0) {
        Step 11 'Geri alma kayd\u0131' 'Undo record'
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
            elseif ($c.What -like 'animation *') { $lines.Add("  adb shell settings put global $($c.What.Substring(10)) 1.0") | Out-Null }
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

    # ---------------------------------------------------------------- 12. bitis
    Step 12 'Bitti' 'Done'
    foreach ($pkg in $InstallOrder) { Good '{0} {1}' '{0} {1}' (Nm $pkg) $manifest.apps.$pkg.versionName }
    Info 'Sonraki g\u00fcncellemeler i\u00e7in bilgisayar gerekmez:' 'Later updates do not need a computer:'
    Info 'Kutu Home > sa\u011f \u00fcstteki \u00e7ark > G\u00fcncellemeleri denetle.' 'Kutu Home > gear at the top left > Check for updates.'
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
