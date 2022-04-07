# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.0] - 2022-04-07

### Changed
- Determine parents of commit by date rather than using the previous commit added.

### Fixed
- Sorting of filenames in tree.
- Error in dateformat.

## [1.1.0] - 2022-04-01

### Changed
- Overdue for minor version update.

### Fixed
- Fix for commit and tag date format.

## [1.0.5] - 2022-03-30

### Added
- Function to get HEAD.
- Functions to get first and last commit.
- Functions to check if commit has, and get the parent.
- Option to specify depth when retrieving list of objects with their children.

### Changed
- Reformat

### Fixed
- Return tree if hash matches when calling Commit.byHash

## [1.0.4] - 2022-03-29

### Added 
- Function to get branch by name.
- Function to get commit by tag.

## [1.0.3] - 2022-03-23

### Added
- Initial release
