## file structure

Database scripts must be organized into 2 levels of subdirectories starting from the current path.

First level directory describes the database id (database system and version) compatible to the scripts inside it.
Database id must be defined in configuration.

Second level directory describes versions of db objects. It must follow the pattern "v###" e.g: v001. During startup the
db objects detects the latest version of installed scripts and applies to that the next versions of scripts one by one.

The scripts must contain following files:

* create-db.sql - script for creating db-objects in the database
* migrate-db.sql - optional script for migrating previous version of db objects into current one.
* create-domain.sql - script for creating domain in the database
* migrate-domain.sql - script for migrating domain domain data in the

Sample directory structure:

```
postgres12
  v001
  v002
postgres13
  v001  
```
