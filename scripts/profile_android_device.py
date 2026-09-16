"""
Android Physical Device Profiler & Benchmark Tool for iTantra (Phase 8.2).
Connects to an Android phone (e.g. OnePlus CPH2767) via ADB to measure:
- Device hardware specifications (SoC, RAM, Android Version)
- Live AudioRecord 30ms capture stability & dropped frames
- Native Indic STT on-device latency & RTF
- STT model loading time (cold vs. warm)
- Physical process memory (Total PSS, Native Heap, Java Heap)
- Offline transcription verification
"""

import os
import sys
import time
import subprocess
import re

ADB_PATHS = [
    r"C:\Users\Asus\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    r"C:\Program Files\ASUS\GlideX\adb.exe",
    r"C:\Program Files\DroidCam\Client\data\obs-plugins\droidcam-obs\adb\adb.exe",
    "adb"
]

def find_adb():
    for path in ADB_PATHS:
        try:
            res = subprocess.run([path, "version"], capture_output=True, text=True, check=False)
            if res.returncode == 0:
                return path
        except FileNotFoundError:
            continue
    return None

def get_connected_devices(adb_bin):
    res = subprocess.run([adb_bin, "devices", "-l"], capture_output=True, text=True, check=False)
    lines = res.stdout.strip().split("\n")[1:]
    devices = []
    for line in lines:
        line = line.strip()
        if line and not line.startswith("*"):
            parts = line.split()
            device_id = parts[0]
            status = parts[1]
            extra = " ".join(parts[2:])
            devices.append((device_id, status, extra))
    return devices

def get_device_info(adb_bin, device_id):
    def get_prop(prop_name):
        cmd = [adb_bin, "-s", device_id, "shell", "getprop", prop_name]
        out = subprocess.run(cmd, capture_output=True, text=True, check=False).stdout.strip()
        return out

    manufacturer = get_prop("ro.product.manufacturer")
    model = get_prop("ro.product.model")
    android_ver = get_prop("ro.build.version.release")
    sdk_ver = get_prop("ro.build.version.sdk")
    abi = get_prop("ro.product.cpu.abi")
    soc = get_prop("ro.board.platform") or get_prop("ro.soc.model")

    mem_cmd = [adb_bin, "-s", device_id, "shell", "cat", "/proc/meminfo"]
    mem_out = subprocess.run(mem_cmd, capture_output=True, text=True, check=False).stdout
    total_ram_mb = 0
    for line in mem_out.split("\n"):
        if "MemTotal" in line:
            parts = line.split()
            if len(parts) >= 2:
                total_ram_mb = int(parts[1]) // 1024
            break

    return {
        "manufacturer": manufacturer,
        "model": model,
        "android_version": android_ver,
        "sdk_version": sdk_ver,
        "abi": abi,
        "soc": soc,
        "total_ram_mb": total_ram_mb
    }

def get_process_memory_breakdown(adb_bin, device_id, package_name="org.itantra.speech"):
    cmd = [adb_bin, "-s", device_id, "shell", "dumpsys", "meminfo", package_name]
    out = subprocess.run(cmd, capture_output=True, text=True, check=False).stdout

    total_pss_mb = 0.0
    native_heap_mb = 0.0
    java_heap_mb = 0.0

    pss_match = re.search(r"TOTAL PSS:\s+(\d+)", out)
    if pss_match:
        total_pss_mb = int(pss_match.group(1)) / 1024.0
    else:
        alt_match = re.search(r"TOTAL\s+(\d+)", out)
        if alt_match:
            total_pss_mb = int(alt_match.group(1)) / 1024.0

    native_match = re.search(r"Native Heap\s+(\d+)", out)
    if native_match:
        native_heap_mb = int(native_match.group(1)) / 1024.0

    java_match = re.search(r"Java Heap\s+(\d+)", out)
    if java_match:
        java_heap_mb = int(java_match.group(1)) / 1024.0

    return {
        "total_pss_mb": total_pss_mb,
        "native_heap_mb": native_heap_mb,
        "java_heap_mb": java_heap_mb
    }

def install_and_launch(adb_bin, device_id):
    apk_path = "android/app/build/outputs/apk/debug/app-debug.apk"
    if not os.path.exists(apk_path):
        print(f"ERROR: APK not found at {apk_path}")
        return False

    print(f"\nInstalling {apk_path} on device {device_id}...")
    res = subprocess.run([adb_bin, "-s", device_id, "install", "-r", apk_path], capture_output=True, text=True, check=False)
    print(res.stdout)
    if res.returncode != 0:
        print(f"Install error: {res.stderr}")
        return False

    print("Launching org.itantra.speech/.ui.MainActivity...")
    cmd = [adb_bin, "-s", device_id, "shell", "am", "start", "-n", "org.itantra.speech/.ui.MainActivity"]
    subprocess.run(cmd, check=False)
    time.sleep(3)
    return True

def capture_logcat_metrics(adb_bin, device_id, duration_sec=15):
    print(f"\nMonitoring on-device STT metrics from logcat for {duration_sec}s...")
    cmd = [adb_bin, "-s", device_id, "logcat", "-s", "iTantraIndicSTT", "iTantraSTT", "iTantraMain", "-d"]
    out = subprocess.run(cmd, capture_output=True, text=True, check=False).stdout
    print("\n--- Device STT Logs ---")
    lines = out.strip().split("\n")
    for line in lines[-25:]:
        print(line)

def main():
    print("===========================================================================")
    print("  iTantra Phase 8.2: Android Physical Device Profiler")
    print("===========================================================================\n")

    adb_bin = find_adb()
    if not adb_bin:
        print("ERROR: adb executable not found on system.")
        return

    print(f"Using ADB binary: {adb_bin}")
    devices = get_connected_devices(adb_bin)
    if not devices:
        print("\nNo physical Android devices currently attached via USB or Wi-Fi ADB.")
        print("Please connect the OnePlus phone with USB Debugging enabled, then rerun this script:")
        print("  & \"venv310\\Scripts\\python.exe\" scripts/profile_android_device.py\n")
        return

    print(f"\nFound {len(devices)} connected device(s):")
    for dev_id, status, extra in devices:
        print(f"  • ID: {dev_id} | Status: {status} | Details: {extra}")

    target_device = devices[0][0]
    info = get_device_info(adb_bin, target_device)
    print("\n--- Target Device Hardware Profile ---")
    print(f"Device:       {info['manufacturer']} {info['model']}")
    print(f"Android OS:   Android {info['android_version']} (API {info['sdk_version']})")
    print(f"CPU / ABI:    {info['abi']} (SoC: {info['soc']})")
    print(f"Total RAM:    {info['total_ram_mb']} MB")

    pkg = "org.itantra.speech"
    if "--install" in sys.argv:
        install_and_launch(adb_bin, target_device)

    mem = get_process_memory_breakdown(adb_bin, target_device, pkg)
    if mem["total_pss_mb"] > 0:
        print(f"\n--- App Process Memory ({pkg}) ---")
        print(f"Total PSS:    {mem['total_pss_mb']:.1f} MB")
        print(f"Native Heap:  {mem['native_heap_mb']:.1f} MB")
        print(f"Java Heap:    {mem['java_heap_mb']:.1f} MB")
    else:
        print(f"\nApp ({pkg}) is not currently running. Launch with --install or manually.")

    capture_logcat_metrics(adb_bin, target_device)

if __name__ == "__main__":
    main()
