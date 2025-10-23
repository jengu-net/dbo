# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**DBO (Database Objects)** is an enterprise-grade data persistence layer for microservices that enables true database separation with standardized data lifecycle management.

This is a multi-module Gradle project providing cluster-friendly object storage with built-in archiving, audit trails, and event streaming capabilities.

## Project Context

### Purpose
DBO solves database separation challenges in microservices architecture while providing standardized data lifecycle management for the **Structurize** service and future healthcare microservices.

### Parent Project
Part of the Jengu Net organization - provides infrastructure for healthcare interoperability services, specifically supporting the Structurize medical document conversion platform.

### Key Business Requirements
- **EU Data Sovereignty**: All data stored in EU-region PostgreSQL
- **Multi-Tenant SaaS**: Application-level tenant isolation (not infrastructure separation)
- **GDPR Compliance**: Data minimization, right to erasure, audit trails
- **Non-Critical Service**: Supports human-in-the-loop workflows, not real-time clinical decisions

## Architecture

### Multi-Module Structure

```
dbo/
├── db-objects/                      # Core storage abstractions
├── db-objects-postgres/             # PostgreSQL implementation (reactive R2DBC)
├── db-objects-kafka/                # Kafka event streaming integration
├── db-objects-spring/               # Spring Boot auto-configuration & DI
├── db-objects-fhir/                 # FHIR-specific storage optimizations
├── db-objects-spring-petclinic/     # Reference implementation with Spring Boot
└── documentation/                   # Antora-based technical documentation
```

**Note**: Legacy Micronaut modules (db-objects-micronaut, db-objects-micronaut-petclinic) exist but will be migrated to Spring Boot.

### Core Capabilities

**Object Storage**:
- CRUD operations by object ID
- Object type and version management
- Multiple identifier support (automatic rebuilding)
- Criteria-based search (envelope extraction from payload)

**Cluster-Friendly**:
- Multi-instance concurrent access
- Auto-setup and auto-upgrade database structure
- Liquibase migrations with session locks
- Reactive/non-blocking via R2DBC

**References & Subscriptions**:
- Object references with relationship types
- Subscribe to changes in referenced objects
- One-to-many relationship support

**Data Lifecycle**:
- Built-in archiving capabilities
- Temporary restoration from archive
- Audit trails and changelog tracking
- Event streaming via Kafka

**Multi-Tenancy**:
- Tenant isolation at application level
- Tenant-specific quality metrics
- User correction tracking per tenant

## Technology Stack

### Framework & Language
- **Language**: Java 21 (modern features, enterprise support)
- **Framework**: Spring Boot 3.x (industry standard, mature ecosystem)
- **Build**: Gradle multi-module project

### Database
- **Primary**: PostgreSQL 12+ (ACID compliance, JSON support, healthcare standard)
- **Access Pattern**: Reactive/non-blocking via R2DBC (Spring Data R2DBC)
- **Schema Management**: Liquibase with session locks for cluster-friendly migrations
- **Optimization**: Domain-specific tables and functions, JSON storage for payloads

### Event Streaming
- **Platform**: Apache Kafka (high throughput, persistent, distributed)
- **Use Cases**:
  - Object change notifications (create, update, delete)
  - Audit trail distribution
  - Event sourcing for data stores
  - Inter-service data synchronization

### Spring Boot Integration
- Auto-configuration via db-objects-spring module
- Dependency injection for storage components
- R2DBC connection pooling and configuration
- Kafka integration via Spring Kafka

## Key Concepts

### Node
An instance of the DB Objects system identified by node-id.
- Shares state with other nodes through shared database
- Coordinates administrative activities in clustered environments
- Manages storage state

### Domain
A non-shared discrete "database" for one microservice.
- Automatically upgradeable set of domain-specific tables and functions
- Provides high-performing near real-time CRUD operations
- Supports storage "streaming" functionality for change publishing

### Storage
Storage for concrete type (class) of objects serialized to JSON.
- Always bound to one Domain
- Provides filtering, ordering, full-text search
- Additional identifier management
- Custom hooks for business logic and data structure creation

### Database
Abstraction for communicating with PostgreSQL in meaningful ways.
- PostgreSQL 12+ specific optimized implementation
- Reactive access via R2DBC

## Common Development Tasks

### Building the Project

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :db-objects-postgres:build
./gradlew :db-objects-spring:build

# Run tests
./gradlew test

# Clean build
./gradlew clean build
```

### Running the Reference Implementation

```bash
# Run Spring Boot petclinic example
./gradlew :db-objects-spring-petclinic:bootRun
```

### Building Documentation

```bash
# Build Antora documentation
./gradlew :documentation:buildDocs

# Or build directly with Antora
cd documentation
npx antora antora-playbook.yml
```

## Integration with Structurize

DBO is used by Structurize for:

**FHIR Bundle Storage**:
- Store AI-generated FHIR proposals
- Store user-validated FHIR bundles
- Version tracking of FHIR resources

**Document Metadata**:
- Original document references
- Processing workflow status
- Tenant association

**User Correction Tracking**:
- Store diffs between AI proposals and user edits
- Track correction patterns by document type/language
- Privacy-preserving analytics (corrections stored separate from PHI)

**Quality Metrics**:
- Field-level accuracy rates
- Entity extraction precision/recall
- FHIR mapping correctness
- Time-to-validation metrics

**Audit Trails**:
- Complete history of document processing
- User validation actions
- Model version tracking
- Compliance logging for GDPR

## Multi-Tenant Considerations

**Application-Level Isolation**:
- Tenant ID included in all storage operations
- Kafka events scoped by tenant
- No cross-tenant data queries

**GDPR Compliance**:
- Right to erasure: Archive and delete by tenant
- Data minimization: Store only necessary correction metadata
- Audit trails: Complete tracking of data access

**Performance Optimization**:
- Tenant-specific quality metric aggregation
- Efficient querying with tenant ID indexing

## Healthcare Data Handling

**CRITICAL**: This storage layer handles Protected Health Information (PHI)

- **NEVER** log PHI in plain text
- **ALWAYS** encrypt sensitive data at rest (handled by PostgreSQL)
- **ALWAYS** validate and sanitize inputs
- **NEVER** commit credentials or connection strings
- Use de-identified data for testing
- Follow GDPR and healthcare compliance guidelines

## Important Notes

- **Cluster Coordination**: Administrative tasks use database-level locks to prevent conflicts
- **Domain Versioning**: Automatic schema migration via Liquibase (cluster-friendly)
- **Node State Sharing**: Nodes coordinate through shared database state
- **Event Publishing**: All storage changes can publish to Kafka for downstream consumers
- **Reactive Access**: Use R2DBC for non-blocking database operations
- **Multi-Tenancy**: Always include tenant context in storage operations

## Resources

- Parent Project: `../CLAUDE.md` (root-level context)
- Documentation: `./documentation/` (Antora-based technical docs)
- Reference Implementation: `./db-objects-spring-petclinic/`

## Rules

- Follow KISS (Keep It Simple, Stupid) principle
- Follow YAGNI (You Aren't Gonna Need It) principle
- Code must be testable and maintainable
- Use Spring Boot conventions and best practices
- Maintain backward compatibility in storage abstractions
