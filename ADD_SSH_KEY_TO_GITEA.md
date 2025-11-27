# Add SSH Key to Gitea

Your SSH public key is ready, but it needs to be added to your Gitea account.

## Your SSH Public Key

```
ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQC5FfcCk1Ks4kXjuy4uUDzuypvYsqZR0vVl0qkQRZTJqOiPx4zyj7418bFqUcttuyBA34BFwaUw5O0R1WtdtIGwPUNgcwwGOukTApLOCXN4i6V973IN9gtkEJNmaF9fgDPQXE31gWeGswvZlV1v+0PX/cpc/YRc7L2hWRAXgttIugZrG7nxJOfLbRZOpXT4igArtK9sl03fdLxN8tHowl3fQG00zMOZePpU9zZoppod5QslrD4cxctmLgIlKfPgAnHMd1dL0RzLJ5oUkH/CCeYamp6BkeKlknFRmEA3VBjZcGY7xNwZcStkvovs1zAhazx6re9l/fDeodlMuKaSMOeDhZMjP1EjsvCsOCHhrWon4o3ApayHp+kOYVDEkbt9Q8HWbIPP9SnXDlHqqpRkUQMCMd5ugroDHyYix4z89vA+pC1y9oXD+Sq6jsWrKI5li4QZl0caPK/ipbEGH3Ussaf350jr1iX7SXyIE0XHjNMtenTYY9axN3ER7djGKQ0arFLNZXq+bMsaCC7RAnbr8LG6/b+T2muHXwvZJb4H4ZGBfIsY/miq0ExVeMnxeLerYYyjkls6SzDarcutsNzKg0j4N2ZvcOUOQ2yxRi9Kz6OLoasGbMBqpRKzQVzF6ycKdjtD/SUYAXOENzTZWdIppcRj9a47T2IqcPItOxq9/+iHSQ== andy@homelab
```

## Quick Copy Command

```bash
cat ~/.ssh/id_rsa.pub
```

Then copy the output.

## Steps to Add SSH Key to Gitea

### 1. Log in to Gitea
Open http://192.168.0.59:3000 and log in with your credentials.

### 2. Go to Settings
Click your profile picture (top right) → **Settings**

### 3. Navigate to SSH Keys
In the left sidebar, click **SSH / GPG Keys**

### 4. Add New Key
1. Click **Add Key** button
2. **Key Name**: `Pop!_OS Desktop` (or any name you prefer)
3. **Content**: Paste your SSH public key (the entire line starting with `ssh-rsa`)
4. Click **Add Key**

### 5. Verify
You should see your key listed with:
- Name: Pop!_OS Desktop
- Fingerprint: (automatically calculated)
- Added: (current date)

## Test SSH Connection

After adding the key, test the connection:

```bash
ssh -T git@192.168.0.59
```

**Expected output**:
```
Hi there, ameggs! You've successfully authenticated, but Gitea does not provide shell access.
```

If you see this message, SSH is working! ✅

## Alternative: Use HTTPS Instead

If you prefer not to use SSH, you can use HTTPS:

```bash
cd ~/OpenUSBIP
git remote add origin http://192.168.0.59:3000/ameggs/OpenUSBIP.git
git push -u origin main
```

You'll be prompted for your Gitea username and password.

## After Adding SSH Key

Once SSH is set up, you can use SSH URLs for git operations:

```bash
cd ~/OpenUSBIP

# Add SSH remote
git remote add origin git@192.168.0.59:ameggs/OpenUSBIP.git

# Push to Gitea
git push -u origin main
```

**Benefits of SSH**:
- ✅ No password prompts
- ✅ More secure
- ✅ Faster authentication
- ✅ Works with automation scripts

## Troubleshooting

### SSH Connection Still Fails

1. **Check SSH service on Gitea**:
   ```bash
   ssh root@192.168.0.59 "systemctl status sshd"
   ```

2. **Check Gitea SSH port**:
   - Default is port 22
   - May be configured differently in Gitea

3. **Check SSH key format**:
   - Must be the entire line
   - Starts with `ssh-rsa`
   - Ends with `andy@homelab`

4. **Try verbose SSH**:
   ```bash
   ssh -vT git@192.168.0.59
   ```

### Key Already Exists

If Gitea says the key already exists:
- It may be added to another account
- Check if you have multiple Gitea accounts
- Generate a new SSH key if needed

## Next Steps

1. ✅ Add SSH key to Gitea (this guide)
2. ✅ Test SSH connection
3. ✅ Create OpenUSBIP repository on Gitea
4. ✅ Push code using SSH or HTTPS

---

**Note**: This SSH key is already used for accessing your homelab servers. Adding it to Gitea allows you to push/pull code without passwords.
