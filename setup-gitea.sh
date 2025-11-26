#!/bin/bash
# Setup script to push OpenUSBIP to Gitea server

GITEA_URL="http://192.168.0.59:3000"
GITEA_USER="${1:-ameggs}"
REPO_NAME="OpenUSBIP"

echo "========================================="
echo "OpenUSBIP - Gitea Setup Script"
echo "========================================="
echo ""
echo "Gitea Server: $GITEA_URL"
echo "Username: $GITEA_USER"
echo "Repository: $REPO_NAME"
echo ""

# Check if git is initialized
if [ ! -d .git ]; then
    echo "❌ Error: Not a git repository. Run 'git init' first."
    exit 1
fi

# Check if we have commits
if ! git rev-parse HEAD >/dev/null 2>&1; then
    echo "❌ Error: No commits found. Create an initial commit first."
    exit 1
fi

echo "📋 Step 1: Create repository on Gitea"
echo "---------------------------------------"
echo "Please create a new repository on Gitea:"
echo ""
echo "1. Open: $GITEA_URL"
echo "2. Click the '+' button (top right) → 'New Repository'"
echo "3. Repository Name: $REPO_NAME"
echo "4. Description: Open Source USB over IP - Free VirtualHere Alternative"
echo "5. Visibility: Private (or Public if you prefer)"
echo "6. ✅ Initialize Repository: NO (we already have code)"
echo "7. Click 'Create Repository'"
echo ""
read -p "Press ENTER when you've created the repository..."

echo ""
echo "📋 Step 2: Add Gitea as remote"
echo "---------------------------------------"

# Remove existing remote if it exists
git remote remove origin 2>/dev/null

# Add new remote
REMOTE_URL="$GITEA_URL/$GITEA_USER/$REPO_NAME.git"
echo "Adding remote: $REMOTE_URL"
git remote add origin "$REMOTE_URL"

if [ $? -eq 0 ]; then
    echo "✅ Remote added successfully"
else
    echo "❌ Failed to add remote"
    exit 1
fi

echo ""
echo "📋 Step 3: Push to Gitea"
echo "---------------------------------------"
echo "Pushing to: $REMOTE_URL"
echo ""

git push -u origin main

if [ $? -eq 0 ]; then
    echo ""
    echo "========================================="
    echo "✅ SUCCESS!"
    echo "========================================="
    echo ""
    echo "Your repository is now available at:"
    echo "$GITEA_URL/$GITEA_USER/$REPO_NAME"
    echo ""
    echo "Next steps:"
    echo "1. View your repository in the browser"
    echo "2. Add a repository description"
    echo "3. Add topics/tags (usb, usbip, raspberry-pi, qt6, python)"
    echo "4. Configure webhooks if needed"
    echo ""
else
    echo ""
    echo "========================================="
    echo "❌ PUSH FAILED"
    echo "========================================="
    echo ""
    echo "Common issues:"
    echo "1. Repository doesn't exist - create it first"
    echo "2. Authentication failed - check username/password"
    echo "3. Network issue - check Gitea server is running"
    echo ""
    echo "To retry manually:"
    echo "  git push -u origin main"
    echo ""
    exit 1
fi
