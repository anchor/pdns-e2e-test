## System Scope Contract

### Target System
- PowerDNS Authoritative Server 4.7.4

### Design Principle
- Wrapper MUST directly expose PowerDNS DNS concepts
- No additional abstraction layers allowed

### Functional Scope
- Zones: create, update, delete
- RRsets: create, modify, delete
- Records: A, AAAA, CNAME, MX, SRV, TXT, CAA, NS, SOA

### Constraints
- Must match PowerDNS API behavior
- Must not introduce extra domain logic beyond DNS model