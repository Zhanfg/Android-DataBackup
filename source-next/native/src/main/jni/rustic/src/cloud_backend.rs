use opendal::{
    Operator,
    blocking,
    options::{ListOptions, ReadOptions},
    services::Gdrive,
};
use rustic_core::{
    ALL_FILE_TYPES, ConfigOptions, Credentials, ErrorKind, FileType, Id, KeyOptions,
    ReadBackend, Repository, RepositoryBackends, RepositoryOptions, RusticError, RusticResult,
    WriteBackend,
};
use std::{
    collections::HashSet,
    sync::{Arc, Mutex, OnceLock},
};
use tokio::runtime::Runtime;

use crate::Result;

struct GdriveBackend {
    operator: blocking::Operator,
    root: String,
    created_pack_dirs: Mutex<HashSet<String>>,
}

fn runtime() -> &'static Runtime {
    static RUNTIME: OnceLock<Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("failed to create OpenDAL runtime")
    })
}

impl GdriveBackend {
    fn new(root: &str, access_token: &str) -> RusticResult<Self> {
        let builder = Gdrive::default()
            .root(root)
            .access_token(access_token);
        let async_operator = Operator::new(builder).map_err(|error| {
            RusticError::with_source(ErrorKind::Backend, "Creating Google Drive operator failed", error)
        })?;
        let _guard = runtime().enter();
        let operator = blocking::Operator::new(async_operator).map_err(|error| {
            RusticError::with_source(ErrorKind::Backend, "Creating blocking Google Drive operator failed", error)
        })?;
        Ok(Self {
            operator,
            root: root.to_string(),
            created_pack_dirs: Mutex::new(HashSet::new()),
        })
    }

    fn path(tpe: FileType, id: &Id) -> String {
        let hex = id.to_hex();
        let hex = hex.as_str();
        if tpe == FileType::Config {
            "config".to_string()
        } else if tpe == FileType::Pack {
            format!("data/{}/{}", &hex[..2], hex)
        } else {
            format!("{}/{}", tpe.dirname(), hex)
        }
    }

    fn ensure_pack_parent(&self, tpe: FileType, id: &Id) -> RusticResult<()> {
        if tpe != FileType::Pack {
            return Ok(());
        }
        let hex = id.to_hex();
        let dir = format!("data/{}/", &hex[..2]);
        let mut created = self.created_pack_dirs.lock().map_err(|_| {
            RusticError::new(ErrorKind::Internal, "Google Drive directory cache is poisoned")
        })?;
        if created.insert(dir.clone()) {
            self.operator.create_dir(&dir).map_err(|error| {
                RusticError::with_source(ErrorKind::Backend, "Creating Google Drive pack directory failed", error)
            })?;
        }
        Ok(())
    }

    fn length_u32(length: u64) -> RusticResult<u32> {
        length.try_into().map_err(|error| {
            RusticError::with_source(ErrorKind::Internal, "Repository object exceeds Rustic u32 size", error)
        })
    }
}

impl ReadBackend for GdriveBackend {
    fn location(&self) -> String {
        format!("opendal:gdrive:{}", self.root)
    }

    fn list_with_size(&self, tpe: FileType) -> RusticResult<Vec<(Id, u32)>> {
        if tpe == FileType::Config {
            return match self.operator.stat("config") {
                Ok(metadata) => Ok(vec![(Id::default(), Self::length_u32(metadata.content_length())?)]),
                Err(error) if error.kind() == opendal::ErrorKind::NotFound => Ok(Vec::new()),
                Err(error) => Err(RusticError::with_source(
                    ErrorKind::Backend,
                    "Reading Google Drive repository config metadata failed",
                    error,
                )),
            };
        }

        let prefix = format!("{}/", tpe.dirname());
        let options = ListOptions {
            recursive: true,
            ..Default::default()
        };
        let entries = self.operator.lister_options(&prefix, options).map_err(|error| {
            RusticError::with_source(ErrorKind::Backend, "Listing Google Drive repository failed", error)
        })?;

        entries
            .filter_map(|entry| entry.ok())
            .filter(|entry| entry.metadata().is_file())
            .filter_map(|entry| {
                let id = Id::parse_some(entry.name(), tpe)?;
                Some(Self::length_u32(entry.metadata().content_length()).map(|size| (id, size)))
            })
            .collect()
    }

    fn read_full(&self, tpe: FileType, id: &Id) -> RusticResult<bytes::Bytes> {
        let path = Self::path(tpe, id);
        Ok(self.operator.read(&path).map_err(|error| {
            RusticError::with_source(ErrorKind::Backend, "Reading Google Drive repository object failed", error)
        })?.to_bytes())
    }

    fn read_partial(
        &self,
        tpe: FileType,
        id: &Id,
        _cacheable: bool,
        offset: u32,
        length: u32,
    ) -> RusticResult<bytes::Bytes> {
        let path = Self::path(tpe, id);
        let options = ReadOptions {
            range: (u64::from(offset)..u64::from(offset) + u64::from(length)).into(),
            ..Default::default()
        };
        Ok(self.operator.read_options(&path, options).map_err(|error| {
            RusticError::with_source(ErrorKind::Backend, "Reading Google Drive repository range failed", error)
        })?.to_bytes())
    }

    fn warmup_path(&self, tpe: FileType, id: &Id) -> String {
        Self::path(tpe, id)
    }
}

impl WriteBackend for GdriveBackend {
    fn create(&self) -> RusticResult<()> {
        for tpe in ALL_FILE_TYPES {
            let dir = format!("{}/", tpe.dirname());
            self.operator.create_dir(&dir).map_err(|error| {
                RusticError::with_source(ErrorKind::Backend, "Creating Google Drive repository directory failed", error)
            })?;
        }
        Ok(())
    }

    fn write_bytes(
        &self,
        tpe: FileType,
        id: &Id,
        _cacheable: bool,
        content: bytes::Bytes,
    ) -> RusticResult<()> {
        self.ensure_pack_parent(tpe, id)?;
        let path = Self::path(tpe, id);
        self.operator.write(&path, content).map_err(|error| {
            RusticError::with_source(ErrorKind::Backend, "Writing Google Drive repository object failed", error)
        })?;
        Ok(())
    }

    fn remove(&self, tpe: FileType, id: &Id, _cacheable: bool) -> RusticResult<()> {
        let path = Self::path(tpe, id);
        self.operator.delete(&path).map_err(|error| {
            RusticError::with_source(ErrorKind::Backend, "Deleting Google Drive repository object failed", error)
        })?;
        Ok(())
    }
}

fn backends(root: &str, access_token: &str) -> RusticResult<RepositoryBackends> {
    Ok(RepositoryBackends::new(
        Arc::new(GdriveBackend::new(root, access_token)?),
        None,
    ))
}

pub fn init_gdrive_repository(root: &str, access_token: &str, password: &str) -> Result<()> {
    Repository::new(&RepositoryOptions::default(), &backends(root, access_token)?)?.init(
        &Credentials::password(password),
        &KeyOptions::default(),
        &ConfigOptions::default(),
    )?;
    Ok(())
}

pub fn gdrive_repository_exists(root: &str, access_token: &str) -> Result<bool> {
    let repo = Repository::new(&RepositoryOptions::default(), &backends(root, access_token)?)?;
    Ok(repo.config_id()?.is_some())
}

pub fn validate_gdrive_repository(root: &str, access_token: &str, password: &str) -> Result<()> {
    Repository::new(&RepositoryOptions::default(), &backends(root, access_token)?)?
        .open(&Credentials::password(password))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pack_path_preserves_rustic_layout() {
        let id: Id = "03dc1178e4e54f69beaf35dd9d4256a5a600e9fa3452b9db80bd649938923e67"
            .parse()
            .unwrap();
        assert_eq!(
            GdriveBackend::path(FileType::Pack, &id),
            "data/03/03dc1178e4e54f69beaf35dd9d4256a5a600e9fa3452b9db80bd649938923e67"
        );
    }
}
