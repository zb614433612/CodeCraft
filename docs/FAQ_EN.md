> 🌐 中文版：[🇨🇳 FAQ](./FAQ.md)
# Frequently Asked Questions (FAQ)

> Version: v1.0.5 | Last Updated: 2026-05-28 | For: All Users

---

## Q1: Error "Port 8084 already in use" on startup, what to do?

**Answer**: This means a CodeCraft instance is already running, or another program is occupying port 8084.

**Solution**:
- Windows: Open Command Prompt and run `netstat -ano | findstr 8084`, find the PID and use Task Manager to end the process
- Mac/Linux: Run `lsof -i :8084` to find the PID, then `kill -9 <PID>`
- Return to CodeCraft and restart

---

## Q2: What if I forgot my admin password?

**Answer**: The default password is `123456`. If you changed the password and forgot it, you'll need to reset the database.

**Solution**:
1. Stop CodeCraft
2. Delete the `data/` directory (this will clear all conversations and settings, be careful!)
3. Restart CodeCraft — the database will be recreated and the default account `admin/123456` will be restored

---

## Q3: Frontend can't connect to backend, what to do?

**Answer**: Usually a CORS (Cross-Origin Resource Sharing) or port issue.

**Checklist**:
1. Confirm backend has started successfully (terminal shows `Started AgentDeepseekApplication`)
2. Confirm accessing `http://localhost:8084` (not port 5173 unless in dev mode)
3. Confirm `application.yml` has correct CORS configuration
4. Try clearing browser cache or using incognito mode

---

## Q4: Wrong DeepSeek API Key, what to do?

**Answer**: API Key can be modified in the Settings page.

**Steps**:
1. Click the settings gear icon in the left menu
2. Go to the "System Config" page
3. Find "DeepSeek API Key", enter the correct key
4. Click Save

---

## Q5: Electron app white screen after update, what to do?

**Answer**: Usually caused by old version cache conflicts.

**Solution**:
1. Close CodeCraft
2. Delete the cache directory:
   - Windows: `%APPDATA%\CodeCraft\Cache`
   - macOS: `~/Library/Application Support/CodeCraft/Cache`
3. Restart the app

---

## Q6: P2P connection fails, what to do?

**Answer**: Possible causes include firewall blocking, network issues, or incompatible versions.

**Checklist**:
1. Ensure both devices are on the same local network
2. Check if firewall is blocking port 9527 (CodeCraft auto-registers firewall rules on startup)
3. Try manually adding a firewall exception rule for port 9527
4. Ensure both devices are running the same version of CodeCraft
5. If QR code pairing fails, try manually entering the connection string

---

## Q7: Tool execution keeps failing, what to do?

**Answer**: Possible causes include insufficient permissions, invalid file paths, or environment issues.

**Checklist**:
1. Check if execution mode is set to "Manual" (manual mode requires confirmation for each operation)
2. Check if the file path is within the project directory (path traversal is prevented)
3. Check if the target file exists
4. View the error message in the tool execution result card

---

## Q8: How to add a new AI Agent?

**Answer**: You can create custom Agents on the Agent Management page.

**Steps**:
1. Click "Configure" → "Agent Management" in the left menu
2. Click the "New Agent" button
3. Fill in Agent name, description, system prompt, tool selection, etc.
4. Click Save
5. The new Agent will appear in the Agent selector at the top of the chat page

---

## Q9: How to improve AI response quality?

**Answer**: Several approaches:

- **Optimize prompts**: Use the ✨ Optimize button to let AI rewrite ambiguous descriptions
- **Use Manual Mode**: Manually confirm each operation to ensure accuracy
- **Create Skills**: Create reusable skill templates for repetitive tasks
- **Adjust Model**: Try different models (Flash for speed, Pro for quality)
- **Enable Thinking Mode**: Deep thinking helps with complex tasks

---

## Q10: How to report bugs or request features?

**Answer**:

- **Bug Reports**: Go to [GitHub Issues](https://github.com/zb614433612/CodeCraft/issues), describe the issue in detail with reproduction steps and environment info
- **Feature Requests**: Also submit via Issues, describe the feature and the problem it solves
- **Security Vulnerabilities**: Do NOT report via public Issues; contact the maintainer directly via email

---

> 💡 Not finding your answer? Check [ARCHITECTURE.md](./ARCHITECTURE.md) and [DEV_QUICKREF.md](./DEV_QUICKREF.md), or submit an Issue on GitHub.
