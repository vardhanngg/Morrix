# Morix — Deployment Guide (GitHub + Render)

Follow these steps exactly. No prior deployment experience needed.

---

## PART 1 — Push to GitHub

### Step 1 — Create a new repository on GitHub
1. Go to https://github.com/new
2. Repository name: `morix`
3. Set it to **Private**
4. Do NOT tick "Add a README" — leave everything unchecked
5. Click **Create repository**

### Step 2 — Upload your code
1. On the new repo page, click **"uploading an existing file"**
2. Drag and drop ALL the files from the morix folder:
   - Dockerfile
   - docker-entrypoint.sh
   - pom.xml
   - .gitignore
   - The entire src/ folder
3. Click **Commit changes**

---

## PART 2 — Create the Database on Render

### Step 3 — Create a free PostgreSQL database
1. Go to https://dashboard.render.com
2. Click **New +** → **PostgreSQL**
3. Fill in: Name = morix-db, Region = Singapore, Plan = Free
4. Click **Create Database**
5. Wait ~1 min, then copy the **Internal Database URL** shown on the page
   (looks like: postgres://user:pass@dpg-xxxx/morix_db)

---

## PART 3 — Deploy the App

### Step 4 — Create a Web Service
1. Click **New +** → **Web Service**
2. Connect your `morix` GitHub repository

### Step 5 — Configure it
| Field | Value |
|---|---|
| Name | morix |
| Region | Singapore |
| Branch | main |
| Runtime | **Docker** |
| Plan | Free |

### Step 6 — Add environment variable
In the **Environment Variables** section add:
- Key: `DATABASE_URL`
- Value: *(the Internal Database URL from Step 3)*

### Step 7 — Deploy
Click **Create Web Service**. Build takes ~3-4 min.
When logs show `[Morix] App started OK` — you're live!

Your URL: `https://morix.onrender.com`

---

## Notes
- Free tier sleeps after 15 min idle — first load after sleep takes ~30s
- Every GitHub push auto-redeploys
- DATABASE_URL must be the **Internal** URL (not External)
