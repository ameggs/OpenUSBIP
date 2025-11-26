# Pushing OpenUSBIP to Gitea

This guide will help you push the OpenUSBIP project to your self-hosted Gitea server.

## Quick Setup

### Option 1: Automated Script (Recommended)

```bash
cd ~/OpenUSBIP
./setup-gitea.sh
```

Follow the prompts to create the repository and push the code.

### Option 2: Manual Setup

#### Step 1: Create Repository on Gitea

1. Open your Gitea instance: http://192.168.0.59:3000
2. Log in with your credentials
3. Click the **+** button (top right) → **New Repository**
4. Fill in the details:
   - **Owner**: ameggs (or your username)
   - **Repository Name**: `OpenUSBIP`
   - **Description**: `Open Source USB over IP - Free VirtualHere Alternative`
   - **Visibility**: Private (or Public)
   - **Initialize Repository**: ❌ **NO** (we already have code)
   - **Add .gitignore**: None (we have one)
   - **Add License**: None (we have GPL-3.0)
   - **Add README**: None (we have one)
5. Click **Create Repository**

#### Step 2: Add Remote and Push

```bash
cd ~/OpenUSBIP

# Add Gitea as remote
git remote add origin http://192.168.0.59:3000/ameggs/OpenUSBIP.git

# Push to Gitea
git push -u origin main
```

You'll be prompted for your Gitea username and password.

## Repository Information

### Project Details
- **Name**: OpenUSBIP
- **Description**: Open Source USB over IP - Free VirtualHere Alternative
- **License**: GPL-3.0
- **Language**: Python
- **Version**: 0.1.0

### Suggested Topics/Tags
Add these topics to your Gitea repository for better discoverability:
- `usb`
- `usbip`
- `raspberry-pi`
- `qt6`
- `pyside6`
- `python`
- `cross-platform`
- `self-hosted`
- `virtualization`
- `homelab`

### Repository Settings

#### Recommended Settings
1. **Description**: "Open Source USB over IP - Free VirtualHere Alternative"
2. **Website**: Leave blank or add your homelab URL
3. **Topics**: Add the tags listed above
4. **Default Branch**: `main`
5. **Visibility**: Private (recommended for homelab)

#### Optional Settings
- **Issues**: Enable if you want to track bugs/features
- **Wiki**: Enable for additional documentation
- **Projects**: Enable for project management
- **Releases**: Enable to create version releases

## What's Being Pushed

### Files Included (33 files, 6438 lines)

#### Documentation
- `README.md` - Main project documentation
- `CHANGELOG.md` - Version history and release notes
- `TESTING.md` - Comprehensive testing checklist
- `LICENSE` - GPL-3.0 license
- `docs/README.md` - Documentation index
- `docs/SYSTEM_TRAY_GUIDE.md` - System tray user guide

#### Source Code
- `src/core/` - Core USB/IP functionality
  - `usbip_wrapper.py` - USB/IP command wrapper
  - `server_discovery.py` - mDNS auto-discovery
  - `config_manager.py` - Configuration management
- `src/gui/` - Qt6 GUI components
  - `main_window.py` - Main application window
  - `server_widget.py` - Server list widget
  - `device_widget.py` - Device list widget
  - `system_tray.py` - System tray integration
- `src/utils/` - Utility modules
  - `platform_utils.py` - Platform detection
  - `logger.py` - Logging configuration

#### Scripts
- `openusbip_client.py` - Client entry point
- `openusbip_server.py` - Server entry point
- `install-server.sh` - One-command Raspberry Pi installer
- `setup.sh` - Development setup script

#### Configuration
- `requirements.txt` - Python dependencies
- `.gitignore` - Git ignore rules

#### Resources
- `resources/icons/openusbip.svg` - Application icon

### Files Excluded (via .gitignore)
- Python cache files (`__pycache__/`, `*.pyc`)
- Virtual environments (`venv/`, `env/`)
- User data (`.openusbip/`, `*.db`)
- Logs (`*.log`, `logs/`)
- IDE files (`.vscode/`, `.idea/`)
- Session summaries (`SESSION_SUMMARY.md`)
- Sensitive files (`.homelab-access-info.md`)

## After Pushing

### Verify the Push

1. Open your repository: http://192.168.0.59:3000/ameggs/OpenUSBIP
2. Check that all files are present
3. Verify the README displays correctly
4. Check the commit history

### Add Repository Metadata

1. Go to **Settings** → **Basic Settings**
2. Add description and topics
3. Set default branch to `main`
4. Configure visibility (Private/Public)

### Optional: Create a Release

```bash
cd ~/OpenUSBIP

# Tag the current version
git tag -a v0.1.0 -m "Release v0.1.0 - Initial release with system tray integration"

# Push the tag
git push origin v0.1.0
```

Then create a release on Gitea:
1. Go to **Releases** → **New Release**
2. Select tag: `v0.1.0`
3. Release title: `v0.1.0 - Initial Release`
4. Description: Copy from `CHANGELOG.md`
5. Attach files if needed (AppImage, .deb, etc.)
6. Click **Publish Release**

## Future Updates

### Making Changes

```bash
cd ~/OpenUSBIP

# Make your changes
# ...

# Stage and commit
git add -A
git commit -m "Description of changes"

# Push to Gitea
git push origin main
```

### Creating Feature Branches

```bash
# Create a new branch
git checkout -b feature/new-feature

# Make changes and commit
git add -A
git commit -m "Add new feature"

# Push branch to Gitea
git push origin feature/new-feature
```

Then create a Pull Request on Gitea to merge into `main`.

## Troubleshooting

### Authentication Failed

If you get authentication errors:

1. **Check credentials**: Make sure username/password are correct
2. **Use token**: Create a personal access token in Gitea
   - Settings → Applications → Generate New Token
   - Use token as password when pushing

### Repository Already Exists

If the repository already exists:

```bash
# Remove old remote
git remote remove origin

# Add correct remote
git remote add origin http://192.168.0.59:3000/ameggs/OpenUSBIP.git

# Force push (⚠️ WARNING: This will overwrite remote)
git push -u origin main --force
```

### Push Rejected

If push is rejected due to conflicts:

```bash
# Pull changes first
git pull origin main --rebase

# Resolve conflicts if any
# ...

# Push again
git push origin main
```

### Can't Access Gitea

Check if Gitea is running:

```bash
# Check Gitea container
ssh root@192.168.0.59 "systemctl status gitea"

# Check if port is open
curl -I http://192.168.0.59:3000

# Check from Proxmox
ssh root@192.168.0.193 "pct status 104"
```

## Backup

Your code is now backed up on your Gitea server! To create additional backups:

### Clone to Another Location

```bash
# Clone from Gitea
git clone http://192.168.0.59:3000/ameggs/OpenUSBIP.git /path/to/backup
```

### Export Repository

From Gitea web interface:
1. Go to **Settings** → **Repository**
2. Scroll to **Danger Zone**
3. Click **Export Repository**
4. Download the archive

## Next Steps

1. ✅ Push code to Gitea
2. ✅ Verify repository is accessible
3. [ ] Add repository description and topics
4. [ ] Create v0.1.0 release
5. [ ] Set up webhooks (optional)
6. [ ] Configure CI/CD (optional)
7. [ ] Add collaborators (optional)

## Resources

- **Gitea Documentation**: https://docs.gitea.io/
- **Git Documentation**: https://git-scm.com/doc
- **Your Gitea Instance**: http://192.168.0.59:3000

---

**Note**: This is a self-hosted Gitea instance on your homelab. Make sure to back up your Gitea data regularly!
