Set shell = CreateObject("WScript.Shell")
quote = Chr(34)
command = quote & WScript.Arguments(0) & quote & " -NoProfile -File " & quote & WScript.Arguments(1) & quote & " -Background"
shell.Run command, 0, True
