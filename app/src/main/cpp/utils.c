#include <stdlib.h>
#include <string.h>

#include "byedpi/params.h"
#include "byedpi/error.h"
#include "main.h"
#include "utils.h"

struct params default_params;

void reset_params(void) {
    clear_params(NULL, NULL);
    params = default_params;
}
