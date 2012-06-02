#pragma version(1)
#pragma rs java_package_name(com.squeed.rscamera)

uint32_t width;
uint32_t height;

const bytes_per_pixel = 2;

void root(const int *in, int *out, uint32_t x, uint32_t y) {
	
	int i, j, total;
        int nR, nG, nB;
        int nY, nU, nV;
        int offset = 0;
            
    	i = x / width;
    	j = x % height;
	total = width*height;
    
//        nY = *(pY + i * width + j);
//        nV = *(pUV + (i/2) * width + bytes_per_pixel * (j/2));
//        nU = *(pUV + (i/2) * width + bytes_per_pixel * (j/2) + 1);

	nY = *(in + i * width + j);
        nV = *(in + total + (i/2) * width + bytes_per_pixel * (j/2));
        nU = *(in + total + (i/2) * width + bytes_per_pixel * (j/2) + 1);
    
        // Yuv Convert
        nY -= 16;
        nU -= 128;
        nV -= 128;
    
        if (nY < 0)
            nY = 0;
    
        nB = (int)(1192 * nY + 2066 * nU);
        nG = (int)(1192 * nY - 833 * nV - 400 * nU);
        nR = (int)(1192 * nY + 1634 * nV);
    
        nR = min(262143, max(0, nR));
        nG = min(262143, max(0, nG));
        nB = min(262143, max(0, nB));
    
        nR >>= 10; nR &= 0xff;
        nG >>= 10; nG &= 0xff;
        nB >>= 10; nB &= 0xff;
        out[offset++] = (unsigned char)nR;
        out[offset++] = (unsigned char)nG;
        out[offset++] = (unsigned char)nB;

}