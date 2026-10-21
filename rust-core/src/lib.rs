use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use serde::Serialize;
use std::fs::{self, File};
use std::io;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

const OK: &str = "OK";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DirectoryEntry {
    path: String,
    name: String,
    is_directory: bool,
    size_bytes: u64,
    modified_at: u64,
}

fn list_directory(path: &Path) -> io::Result<Vec<DirectoryEntry>> {
    let mut entries = Vec::new();
    for entry in fs::read_dir(path)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        // Do not follow symbolic links: this prevents directory loops and path escapes.
        if file_type.is_symlink() {
            continue;
        }
        let metadata = entry.metadata()?;
        let modified_at = metadata
            .modified()
            .ok()
            .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
            .map(|duration| duration.as_millis().min(u64::MAX as u128) as u64)
            .unwrap_or(0);
        entries.push(DirectoryEntry {
            path: entry.path().to_string_lossy().into_owned(),
            name: entry.file_name().to_string_lossy().into_owned(),
            is_directory: file_type.is_dir(),
            size_bytes: if file_type.is_file() { metadata.len() } else { 0 },
            modified_at,
        });
    }
    entries.sort_by(|a, b| {
        b.is_directory
            .cmp(&a.is_directory)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    Ok(entries)
}

fn validate_target(source: &Path, target: &Path) -> io::Result<()> {
    if target.exists() {
        return Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            "مقصد از قبل وجود دارد",
        ));
    }
    let source_meta = fs::symlink_metadata(source)?;
    if source_meta.file_type().is_symlink() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "عملیات روی پیوند نمادین مجاز نیست",
        ));
    }
    let source_canonical = fs::canonicalize(source)?;
    let parent = target.parent().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "مسیر مقصد نامعتبر است")
    })?;
    let parent_canonical = fs::canonicalize(parent)?;
    let target_canonical = parent_canonical.join(target.file_name().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "نام مقصد نامعتبر است")
    })?);
    if source_meta.is_dir()
        && (target_canonical == source_canonical || target_canonical.starts_with(&source_canonical))
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "نمی‌توان پوشه را درون خودش کپی یا منتقل کرد",
        ));
    }
    Ok(())
}

fn copy_tree(source: &Path, target: &Path) -> io::Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "پیوند نمادین پشتیبانی نمی‌شود",
        ));
    }
    if metadata.is_file() {
        let mut input = File::open(source)?;
        let mut output = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(target)?;
        io::copy(&mut input, &mut output)?;
        return Ok(());
    }
    if !metadata.is_dir() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "نوع فایل پشتیبانی نمی‌شود",
        ));
    }
    fs::create_dir(target)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let child_target = target.join(entry.file_name());
        copy_tree(&entry.path(), &child_target)?;
    }
    Ok(())
}

fn copy_path(source: &Path, target: &Path) -> io::Result<()> {
    validate_target(source, target)?;
    match copy_tree(source, target) {
        Ok(()) => Ok(()),
        Err(error) => {
            // The target was verified absent before the operation, so cleaning it up
            // only removes partial output created by this copy attempt.
            let _ = remove_tree(target);
            Err(error)
        }
    }
}

fn move_path(source: &Path, target: &Path) -> io::Result<()> {
    validate_target(source, target)?;
    match fs::rename(source, target) {
        Ok(()) => Ok(()),
        Err(_) => {
            copy_path(source, target)?;
            remove_tree(source).map_err(|error| {
                io::Error::new(
                    error.kind(),
                    format!("فایل کپی شد اما حذف مبدأ ناموفق بود: {error}"),
                )
            })
        }
    }
}

fn remove_tree(path: &Path) -> io::Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.is_dir() && !metadata.file_type().is_symlink() {
        for entry in fs::read_dir(path)? {
            remove_tree(&entry?.path())?;
        }
        fs::remove_dir(path)
    } else {
        fs::remove_file(path)
    }
}

fn recursive_size(path: &Path) -> io::Result<u64> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() {
        return Ok(0);
    }
    if metadata.is_file() {
        return Ok(metadata.len());
    }
    let mut total = 0_u64;
    for entry in fs::read_dir(path)? {
        total = total.saturating_add(recursive_size(&entry?.path())?);
    }
    Ok(total)
}

fn jstring_to_path(env: &mut JNIEnv, value: &JString) -> io::Result<PathBuf> {
    let value: String = env
        .get_string(value)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error.to_string()))?
        .into();
    if value.trim().is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "مسیر خالی است"));
    }
    Ok(PathBuf::from(value))
}

fn return_string(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn result_string(result: io::Result<()>) -> String {
    match result {
        Ok(()) => OK.to_string(),
        Err(error) => format!("ERR:{error}"),
    }
}

#[no_mangle]
pub extern "system" fn Java_ir_solina_fm_nativecore_RustFileEngine_nativeListDirectory(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let result = jstring_to_path(&mut env, &path)
        .and_then(|path| list_directory(&path).map_err(|error| {
            io::Error::new(error.kind(), error.to_string())
        }));
    let output = match result {
        Ok(entries) => serde_json::to_string(&entries).unwrap_or_else(|_| "[]".to_string()),
        Err(error) => format!("ERR:{error}"),
    };
    return_string(&mut env, output)
}

#[no_mangle]
pub extern "system" fn Java_ir_solina_fm_nativecore_RustFileEngine_nativeCopy(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    target: JString,
) -> jstring {
    let result = jstring_to_path(&mut env, &source).and_then(|source| {
        jstring_to_path(&mut env, &target).and_then(|target| copy_path(&source, &target))
    });
    return_string(&mut env, result_string(result))
}

#[no_mangle]
pub extern "system" fn Java_ir_solina_fm_nativecore_RustFileEngine_nativeMove(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    target: JString,
) -> jstring {
    let result = jstring_to_path(&mut env, &source).and_then(|source| {
        jstring_to_path(&mut env, &target).and_then(|target| move_path(&source, &target))
    });
    return_string(&mut env, result_string(result))
}

#[no_mangle]
pub extern "system" fn Java_ir_solina_fm_nativecore_RustFileEngine_nativeDelete(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let result = jstring_to_path(&mut env, &path).and_then(|path| remove_tree(&path));
    return_string(&mut env, result_string(result))
}

#[no_mangle]
pub extern "system" fn Java_ir_solina_fm_nativecore_RustFileEngine_nativeRecursiveSize(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    jstring_to_path(&mut env, &path)
        .and_then(|path| recursive_size(&path))
        .map(|size| size.min(i64::MAX as u64) as jlong)
        .unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    struct TestDir(PathBuf);

    impl TestDir {
        fn new() -> Self {
            let nonce = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let path = std::env::temp_dir().join(format!(
                "solina-rust-test-{}-{nonce}",
                std::process::id()
            ));
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }
    }

    impl Drop for TestDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn copies_files_and_directories() {
        let temp = TestDir::new();
        let source = temp.0.join("source");
        fs::create_dir(&source).unwrap();
        fs::write(source.join("hello.txt"), "solina").unwrap();
        let target = temp.0.join("copy");
        copy_path(&source, &target).unwrap();
        assert_eq!(fs::read_to_string(target.join("hello.txt")).unwrap(), "solina");
    }

    #[test]
    fn refuses_copy_into_own_subdirectory() {
        let temp = TestDir::new();
        let source = temp.0.join("source");
        fs::create_dir(&source).unwrap();
        let target = source.join("nested");
        assert!(copy_path(&source, &target).is_err());
    }

    #[test]
    fn moves_and_deletes_paths() {
        let temp = TestDir::new();
        let source = temp.0.join("before.txt");
        let target = temp.0.join("after.txt");
        fs::write(&source, "content").unwrap();
        move_path(&source, &target).unwrap();
        assert!(!source.exists());
        assert_eq!(fs::read_to_string(&target).unwrap(), "content");
        remove_tree(&target).unwrap();
        assert!(!target.exists());
    }

    #[test]
    fn calculates_recursive_size() {
        let temp = TestDir::new();
        let folder = temp.0.join("folder");
        fs::create_dir(&folder).unwrap();
        fs::write(folder.join("a"), "123").unwrap();
        fs::write(folder.join("b"), "45").unwrap();
        assert_eq!(recursive_size(&folder).unwrap(), 5);
    }
}
