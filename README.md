# 🏏 AuctionX — Real-Time Auction Backend

**AuctionX** is a real-time cricket player auction platform inspired by IPL-style auctions. This backend powers the entire auction experience using **Spring Boot, WebSocket/STOMP, PostgreSQL, JWT authentication, and a server-authoritative auction engine**.

It synchronizes bids, timers, player reveals, sold/unsold events, team budgets, dashboards, and spectator reactions across organizer, captain, spectator, and projector screens in real time.

🔗 **Live Frontend:** https://auction-frontend-mocha.vercel.app/


##  Features

* 🏏 Real-time cricket player auction engine
* ⚡ WebSocket/STOMP live bidding
* ⏱️ Server-authoritative auction countdown timer
* 🔐 JWT authentication for organizers
* 👥 Join-code authentication for captains
* 👀 Public spectator mode without login
* 🎯 Automatic player selection and auction progression
* 💰 Real-time team budget tracking
* 📊 Post-auction analytics and MVP calculation
* 📈 Bid history and auction pace analytics
* 📁 CSV bulk player import
* 😊 Real-time spectator reactions
* ⏰ Scheduled and automatic tournament startup
* 🛡️ Thread-safe concurrent auction management
* 🖼️ Player image upload and serving
* 📊 Hidden platform-level admin analytics

---

# 🧱 Tech Stack

| Layer             | Technology                                      | Purpose                                                 |
| ----------------- | ----------------------------------------------- | ------------------------------------------------------- |
| Backend           | Spring Boot 3.2                                 | REST APIs, dependency injection and application runtime |
| Language          | Java 17+                                        | Backend development                                     |
| Security          | Spring Security + JWT                           | Stateless organizer authentication                      |
| Real-time         | WebSocket + STOMP                               | Live auction communication                              |
| Database          | PostgreSQL                                      | Persistent application data                             |
| ORM               | JPA + Hibernate                                 | Database persistence                                    |
| Scheduling        | Spring Scheduler                                | Automatic tournament scheduling                         |
| Concurrency       | ConcurrentHashMap, AtomicBoolean, AtomicInteger | Thread-safe auction state                               |
| CSV               | OpenCSV                                         | Bulk player import                                      |
| Password Security | BCrypt                                          | Secure password hashing                                 |
| Build Tool        | Maven                                           | Dependency and build management                         |

---

# 🏗️ Architecture

```text
                         ┌──────────────────────┐
                         │      Frontend        │
                         │   React / Vercel     │
                         └──────────┬───────────┘
                                    │
                     REST APIs + WebSocket/STOMP
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────┐
│                     Spring Boot Backend                    │
│                                                            │
│  ┌───────────────┐       ┌──────────────────────────────┐ │
│  │ REST Layer    │──────▶│       Service Layer          │ │
│  │               │       │                              │ │
│  │ Auth          │       │ AuctionEngineService         │ │
│  │ Tournament    │       │ AuctionTimerService          │ │
│  │ Team          │       │ TournamentSchedulerService   │ │
│  │ Player        │       │ SpectatorService             │ │
│  │ Auction       │       │ BidHistoryService            │ │
│  │ Analytics     │       │                              │ │
│  └───────────────┘       └──────────────┬───────────────┘ │
│                                         │                 │
│                              ┌──────────▼──────────┐      │
│                              │   Auction State     │      │
│                              │ ConcurrentHashMap   │      │
│                              └──────────┬──────────┘      │
│                                         │                 │
│                    ┌────────────────────┴───────────────┐ │
│                    │                                    │ │
│                    ▼                                    ▼ │
│              PostgreSQL                         WebSocket │
│                                                    │      │
└────────────────────────────────────────────────────┼──────┘
                                                     │
                     ┌───────────────────────────────┼─────────────────┐
                     │                               │                 │
                     ▼                               ▼                 ▼
                Organizer                         Captain         Spectator
```

---

# 🔄 Auction State Machine

Each tournament maintains its own auction state:

```text
       ┌─────────┐
       │  IDLE   │
       └────┬────┘
            │
            ▼
    ┌───────────────┐
    │   SPINNING    │
    └───────┬───────┘
            │
            ▼
   ┌─────────────────┐
   │ PLAYER_REVEAL   │
   └────────┬────────┘
            │
            ▼
      ┌───────────┐
      │  BIDDING  │◄──────────────┐
      └─────┬─────┘               │
            │                     │
      Timer expires               │ New bid
            │                     │
            ▼                     │
    ┌───────────────┐             │
    │ SOLD/UNSOLD   │─────────────┘
    └───────┬───────┘
            │
            ▼
       ┌─────────┐
       │  IDLE   │
       └─────────┘
```

The backend is the **single source of truth** for auction state.

Clients never determine the actual auction state locally.

---

# ⚡ Real-Time WebSocket Architecture

AuctionX uses **STOMP over WebSocket** for real-time communication.

### Auction Events

```text
/topic/auction/{tournamentId}
```

Used for:

* Player reveal
* New bids
* Auction phase changes
* Sold events
* Unsold events
* Auction completion

### Timer

```text
/topic/auction/{tournamentId}/timer
```

Broadcasts the server-controlled countdown every second.

### Dashboard

```text
/topic/dashboard/{tournamentId}
```

Broadcasts:

* Team budgets
* Spending
* Remaining balance
* Player assignments

### Spectator Reactions

```text
/topic/reactions/{tournamentId}
```

Used for real-time emoji reactions from spectators.

---

# ⏱️ Server-Authoritative Timer

One of the important design decisions in AuctionX is that the **client does not control the auction timer**.

The server maintains the timer using:

```text
AuctionTimerService
        │
        ▼
ScheduledExecutorService
        │
        ▼
Server countdown
        │
        ├──► WebSocket timer events
        │
        └──► Automatic auction resolution
```

This prevents issues such as:

* Client refresh resetting the timer
* Different users seeing different countdowns
* Client-side timer manipulation
* Timer drift between multiple screens

When a client reconnects, it receives the current server state.

---

# 🏏 Auction Flow

### 1. Spin Player

Organizer triggers:

```http
POST /api/auction/{id}/spin
```

The engine selects a random player from the remaining pool.

The server broadcasts:

```text
WHEEL_SPINNING
```

followed by:

```text
PLAYER_REVEALED
```

---

### 2. Start Bidding

A 30-second server-side timer starts.

```text
30 → 29 → 28 → ... → 1 → 0
```

Every tick is broadcast through WebSocket.

---

### 3. Place Bid

A captain submits a bid.

The backend validates:

```text
New bid > Current bid
Team has sufficient budget
Team is eligible to bid
Auction is currently in BIDDING phase
```

If valid:

```text
Current Bid → Updated
Bid History → Updated
Timer → Reset
Dashboard → Broadcast
```

---

### 4. Detect Bid War

The backend tracks bidding activity to identify competitive bidding.

A bid war is detected when:

```text
2+ teams
+
2+ bids
```

participate in the player auction.

---

### 5. Timer Expiry

When the server timer reaches zero:

```text
             ┌───────────────┐
             │ Timer = 0     │
             └───────┬───────┘
                     │
              ┌──────▼──────┐
              │ Valid bids? │
              └───┬─────┬───┘
                  YES    NO
                   │      │
                   ▼      ▼
                 SOLD   UNSOLD
```

---

### 6. Sold

For a successful auction:

* Team budget is updated
* Player is assigned to the team
* Auction result is persisted
* Bid timeline is stored
* Dashboard is rebuilt
* WebSocket event is broadcast
* Next player can automatically enter the auction

---

# 🗄️ Database Schema

### Users

```text
users
├── id
├── name
├── email
├── password
├── phone
├── role
└── created_at
```

### Tournaments

```text
tournaments
├── id
├── name
├── sport_type
├── status
├── join_code
├── team_budget
├── bid_increment
├── created_by_user_id
├── scheduled_auction_time
└── expires_at
```

### Teams

```text
teams
├── id
├── team_name
├── captain_name
├── team_color
├── total_budget
├── spent_budget
├── remaining_budget
└── tournament_id
```

### Players

```text
players
├── id
├── name
├── role
├── tier
├── base_price
├── sold_price
├── status
├── photo_path
├── team_id
└── tournament_id
```

### Auction Results

```text
auction_results
├── id
├── player_id
├── team_id
├── tournament_id
├── sold_price
├── base_price
├── total_bids
├── bid_timeline
├── status
└── sold_at
```

---

# 🔐 Authentication & Access Model

| Role      | Authentication                   | Login Required |
| --------- | -------------------------------- | -------------- |
| Organizer | Email + Password + JWT           | ✅              |
| Captain   | 6-character tournament join code | ❌              |
| Spectator | Shareable tournament link        | ❌              |

### Organizer Authentication

```text
Email + Password
       │
       ▼
Spring Security
       │
       ▼
BCrypt password verification
       │
       ▼
JWT generated
       │
       ▼
Authorization: Bearer <token>
       │
       ▼
JwtFilter
```

JWT tokens expire after **24 hours**.

---

# 📡 REST API

## Authentication

```http
POST /api/auth/register
POST /api/auth/login
```

## Tournament

```http
GET  /api/tournament/*
POST /api/tournament/*
```

## Teams

```http
GET  /api/team/*
POST /api/team/*
```

## Players

```http
GET  /api/player/*
POST /api/player/*
```

Includes CSV bulk player upload.

## Lobby

```http
POST /api/lobby/*
```

## Auction

```http
POST /api/auction/{id}/spin
POST /api/auction/{id}/bid
POST /api/auction/{id}/sold
POST /api/auction/{id}/unsold
```

## Post Auction

```http
GET /api/post-auction/{id}/*
```

Provides:

* MVP
* Spending breakdown
* Auction pace
* Player statistics
* Team analytics

## Feedback

```http
POST /api/feedback
```

## Admin

```http
GET /api/admin/*
```

Provides platform-level analytics.

---

# 🧠 Engineering Decisions

| Challenge                                | Solution                                        |
| ---------------------------------------- | ----------------------------------------------- |
| Multiple auctions running simultaneously | `ConcurrentHashMap` keyed by tournament ID      |
| Concurrent bid/timer operations          | Atomic variables + thread-safe state management |
| Client timer manipulation                | Server-authoritative timer                      |
| Client refresh losing auction state      | Server maintains active auction state           |
| JPA circular references                  | Purpose-built DTOs                              |
| Invalid CSV rows                         | Per-row validation and exception handling       |
| Production MultipartFile requirement     | Custom `ByteArrayMultipartFile`                 |
| Player image serving                     | Spring `ResourceHandler`                        |
| Automatic tournament startup             | Spring `@Scheduled` jobs                        |
| Password security                        | BCrypt                                          |
| Organizer authentication                 | Stateless JWT                                   |
| Real-time synchronization                | STOMP WebSocket                                 |
| Historical auction analytics             | Persistent bid timeline + auction results       |

---

# 📊 Post-Auction Analytics

AuctionX stores detailed auction information to generate analytics after the tournament.

### Team Analytics

* Total spending
* Remaining budget
* Players purchased
* Average player price
* Spending by tier
* Spending by role

### Player Analytics

* Base price
* Sold price
* Number of bids
* Number of participating teams
* Bid timeline
* Auction duration

### Auction Analytics

* Total players sold
* Unsold players
* Average auction price
* Auction pace
* Highest bid
* Most expensive player
* Bid-war frequency

---

# 📁 Project Structure

```text
src/
└── main/
    ├── java/
    │   └── com.auctionx/
    │       ├── config/
    │       ├── controller/
    │       ├── dto/
    │       ├── entity/
    │       ├── repository/
    │       ├── security/
    │       ├── service/
    │       └── AuctionXApplication.java
    │
    └── resources/
        ├── application.properties
        └── ...
```

---

# 🚀 Getting Started

## Prerequisites

Make sure you have installed:

* Java 17+
* Maven
* PostgreSQL
* Git

---

## 1. Clone the Repository

```bash
git clone <your-repository-url>

cd auctionx-backend
```

---

## 2. Configure PostgreSQL

Create a PostgreSQL database:

```sql
CREATE DATABASE auctionx;
```

Configure your database in:

```text
src/main/resources/application.properties
```

Example:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/auctionx
spring.datasource.username=your_username
spring.datasource.password=your_password
```

---

## 3. Configure Environment Variables

```text
JWT_SECRET=your-secret-key

DB_URL=jdbc:postgresql://localhost:5432/auctionx
DB_USERNAME=your_username
DB_PASSWORD=your_password
```

**Never commit real passwords, JWT secrets, or database credentials to GitHub.**

Use environment variables or a `.env`/deployment secret manager instead.

---

## 4. Run the Backend

### Windows

```bash
mvnw.cmd spring-boot:run
```

### Linux / macOS

```bash
./mvnw spring-boot:run
```

The backend will start at:

```text
http://localhost:8080
```

---

# 🌐 Live Demo

### Frontend

https://auction-frontend-mocha.vercel.app/

The frontend connects to the AuctionX backend through REST APIs and WebSocket/STOMP.

---

# 🔮 Roadmap

* [ ] Email notifications with Spring Mail
* [ ] Auction reminders
* [ ] Automatic sold summaries
* [ ] Live video stream integration
* [ ] Team/captain chat
* [ ] Auto-bid system
* [ ] Maximum bid limits
* [ ] Advanced auction analytics
* [ ] Redis-based distributed auction state
* [ ] Horizontal backend scaling
* [ ] Production monitoring and observability

---

# 💡 Future Auto-Bid System

One planned feature is automated bidding.

A captain could configure:

```text
Maximum Budget: ₹25,00,000
Target Player: Virat
Auto Bid: Enabled
```

The system could automatically place bids whenever another team raises the price, up to the captain's configured maximum.

---

# 🏆 Why I Built AuctionX

Traditional local cricket auctions are often managed using:

```text
Excel + Paper + Manual Timer + Announcements
```

AuctionX turns that process into a synchronized digital auction where:

```text
Organizer
    ↕
Auction Engine
    ↕
Captains
    ↕
Spectators
    ↕
Projector / Live Dashboard
```

all receive the same auction state in real time.

The main engineering challenge was not simply creating CRUD APIs, but building a **stateful, concurrent, real-time auction engine where the server remains the source of truth**.

---

# 👨‍💻 Author

**Nikhil Kumar**

Built with:

```text
Java
Spring Boot
Spring Security
WebSocket / STOMP
PostgreSQL
JPA / Hibernate
JWT
Maven
```


