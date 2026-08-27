class Alpha {
    int twice(int n) { return n * 2; }
    void run() {
        Beta b = new Beta();
        b.helper();
        twice(21);
    }
}
