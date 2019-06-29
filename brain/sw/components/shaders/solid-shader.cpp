#include "solid-shader.h"

#include "esp_log.h"
#define TAG "#shader"

SolidShader::SolidShader(uint8_t** ppCursor, uint8_t* pEnd) {
    // No additional bytes of configuration
}

SolidShader::~SolidShader() {

}

void
SolidShader::begin(Msg *pMsg, float progress) {
    auto argb = pMsg->readInt();

    // ESP_LOGD(TAG, "argb = %x", argb);

    m_color.R = (argb >> 16) & 0xff;
    m_color.G = (argb >>  8) & 0xff;
    m_color.B = (argb      ) & 0xff;
}

void
SolidShader::apply(uint16_t pixelIndex, uint8_t *colorOut, uint8_t *colorIn) {
    memcpy((void*)colorOut, (void*)&m_color, 3);
}

void
SolidShader::end() {

}
