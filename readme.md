# 🎮 Java Funkin

Welcome to **Java Funkin**.

This project originally started as a personal engine just for me, but since it’s now open source, you’re free to explore, modify, and go wild with it!

---

## ✨ Features

1. **Rendering Mode**
   Convert songs into a no-lag version (FFMPEG REQUIRED)

2. **Extra Keys Support**
   Supports from 1 up to 26, 105, 1000, and even 9999 keys

3. **Optimizations**
   Performance improvements to help the game run smoother

4. **Large JSON Support**
   Can load over **2.1GB+ of JSON files**

---

## 📢 Notice

This engine is open source in accordance with FNF modding guidelines, which encourage sharing source code when distributing mods or engines.

---

## ⚠️ Warning

If you fork this project, please consider giving credit.
It’s not required, but it’s appreciated. Either way, feel free to modify and build on it however you want.

---

## 🛠️ How to Compile

If you have the source code, you can compile it using the following commands:

### 1. Compile

```bash
javac Main.java
```

### 2. Run

```bash
java Main
```

or

```bash
java Main.java
```

---

## 📁 Assets Note

You may notice that no assets are included when compiling.
That’s because this engine was originally made for personal use.

To fix this, you’ll need to set your own asset paths:

### Steps:

1. Open `Playstate.java`
2. Locate the following lines:

   * **1475** → Stage path
   * **1497** → Num path
   * **1499** → Comma path
3. Replace them with your own local file paths

Once updated, everything should work correctly!

---

## 🤝 Final Note

Feel free to fork this project and experiment with it.
If you run into issues, you can open an issue and I’ll try to help when I can.

Happy modding! 🎶


## VS CODE

if you got vs code. look in playstate and search Andre and you should see the paths
and if you got the paths to your own path. your ready to use it
thanks :p
