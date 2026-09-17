import os
import sys
import zipfile
import json

def audit_apk(apk_path):
    print(f"Auditing APK: {apk_path}")
    print(f"Total APK File Size: {os.path.getsize(apk_path):,} bytes ({os.path.getsize(apk_path)/(1024*1024):.2f} MB)")
    
    categories = {
        "assets/models/": 0,
        "assets/": 0,
        "lib/arm64-v8a/": 0,
        "classes.dex": 0,
        "res/": 0,
        "resources.arsc": 0,
        "other": 0
    }
    
    uncompressed_cat = dict(categories)
    file_details = []
    
    with zipfile.ZipFile(apk_path, 'r') as z:
        for info in z.infolist():
            name = info.filename
            comp = info.compress_size
            uncomp = info.file_size
            
            matched = False
            for cat in ["assets/models/", "assets/", "lib/arm64-v8a/", "classes.dex", "res/", "resources.arsc"]:
                if name.startswith(cat):
                    categories[cat] += comp
                    uncompressed_cat[cat] += uncomp
                    matched = True
                    break
            if not matched:
                categories["other"] += comp
                uncompressed_cat["other"] += uncomp
                
            if name.startswith("assets/models/") or name.startswith("lib/arm64-v8a/"):
                file_details.append({
                    "name": name,
                    "compressed": comp,
                    "uncompressed": uncomp,
                    "ratio": comp / uncomp if uncomp > 0 else 0
                })
                
    print("\n--- APK Size Breakdown by Component ---")
    total_comp = sum(categories.values())
    total_uncomp = sum(uncompressed_cat.values())
    for cat in categories:
        comp_mb = categories[cat] / (1024*1024)
        uncomp_mb = uncompressed_cat[cat] / (1024*1024)
        pct = (categories[cat] / total_comp) * 100 if total_comp > 0 else 0
        print(f"  {cat:20s}: {comp_mb:7.2f} MB compressed ({pct:5.1f}%) | {uncomp_mb:7.2f} MB uncompressed")
        
    print(f"  {'TOTAL':20s}: {total_comp/(1024*1024):7.2f} MB compressed | {total_uncomp/(1024*1024):7.2f} MB uncompressed")
    
    print("\n--- Top Model & Native Assets ---")
    file_details.sort(key=lambda x: x["compressed"], reverse=True)
    for f in file_details:
        print(f"  {f['name']:45s} | Comp: {f['compressed']/(1024*1024):6.2f} MB | Uncomp: {f['uncompressed']/(1024*1024):6.2f} MB")
        
    return {
        "categories_compressed": categories,
        "categories_uncompressed": uncompressed_cat,
        "files": file_details
    }

if __name__ == "__main__":
    apk = "android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
    res = audit_apk(apk)
    with open("model_optimization/benchmarks/apk_audit_phase95.json", "w") as f:
        json.dump(res, f, indent=2)
    print("\nSaved APK audit to model_optimization/benchmarks/apk_audit_phase95.json")
