package com.densityfold.backend.root;

interface IRootDensityService {
    int getProcessUid();
    int getCurrentDensity(int displayId);
    int setDensity(int displayId, int dpi);
}
